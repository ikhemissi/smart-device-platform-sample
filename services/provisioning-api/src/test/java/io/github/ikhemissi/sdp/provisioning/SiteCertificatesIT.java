package io.github.ikhemissi.sdp.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import io.github.ikhemissi.sdp.provisioning.azure.EnrollmentGroup;
import io.github.ikhemissi.sdp.provisioning.certificates.TestCertificates;
import io.github.ikhemissi.sdp.provisioning.sites.Reconciliation;
import io.github.ikhemissi.sdp.provisioning.sites.SiteRepository;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Spec site-certificates scenarios against PostgreSQL (least-privilege principal) and in-memory DPS / IoT Hub. */
@SpringBootTest(properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID,
        "sdp.provisioning.reconciliation.enabled=false"})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class SiteCertificatesIT {

    private static final String EDITOR = AccessTokens.withRoles("DeviceData.Write");
    private static final String READER = AccessTokens.withRoles("DeviceData.Read");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static int sites;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private Reconciliation reconciliation;

    @MockitoSpyBean
    private SiteRepository repository;

    private final FakeAzure azure = TestSecurity.AZURE;
    private String site;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ProvisioningDatabase.register(registry);
    }

    @BeforeEach
    void newSite() {
        azure.reset();
        site = "it-" + (++sites) + "-" + Long.toString(System.nanoTime() % 100_000, 36);
    }

    // --- Create a site --------------------------------------------------------------------------------------

    @Test
    void editorCreatesASiteWithNoCertificate() {
        MvcTestResult created = post("/api/v1/sites", EDITOR, Map.of("siteId", site, "displayName", "Nice warehouse"));

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(json(get("/api/v1/sites/" + site + "/certificates", READER)).size()).isZero();
        assertThat(azure.groups).doesNotContainKey(EnrollmentGroup.idOf(site));
        assertThat(audit(site)).extracting(e -> e.get("action").asString()).containsExactly("SITE_CREATED");
    }

    @Test
    void duplicateSiteIsAConflict() {
        post("/api/v1/sites", EDITOR, Map.of("siteId", site, "displayName", "First"));

        assertThat(post("/api/v1/sites", EDITOR, Map.of("siteId", site, "displayName", "Second"))).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void siteIdsAreValidated() {
        MvcTestResult result = post("/api/v1/sites", EDITOR, Map.of("siteId", "Nice--01", "displayName", "x"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors.siteId").isNotNull();
    }

    @Test
    void readersCannotChangeTrust() {
        createSite();
        assertThat(post("/api/v1/sites", READER, Map.of("siteId", site + "-x", "displayName", "x"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(upload(site, READER, TestCertificates.ca("ca").pem().getBytes(StandardCharsets.US_ASCII))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.delete().uri("/api/v1/sites/" + site + "/certificates/" + "a".repeat(64))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READER).exchange()).hasStatus(HttpStatus.FORBIDDEN);
    }

    // --- Add ------------------------------------------------------------------------------------------------

    @Test
    void firstCertificateIsTrustedAsPrimary() {
        createSite();
        TestCertificates.Generated ca = TestCertificates.ca("CN=" + site + " CA");

        MvcTestResult result = upload(site, EDITOR, ca.pem().getBytes(StandardCharsets.US_ASCII));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.certificate.slot").isEqualTo("primary");
        assertThat(result).bodyJson().extractingPath("$.certificate.status").isEqualTo("valid");
        assertThat(result).bodyJson().extractingPath("$.certificate.addedBy").isEqualTo(AccessTokens.USER_OID);
        EnrollmentGroup group = azure.groups.get(EnrollmentGroup.idOf(site));
        assertThat(group.enabled()).isTrue();
        assertThat(group.siteId()).isEqualTo(site);
        assertThat(azure.certificates.get(group.primary())).isEqualTo(TestCertificates.pem(ca.certificate()).replace("\r", ""));
    }

    @Test
    void secondCertificateIsTrustedAsSecondaryThenAThirdIsRefused() {
        createSite();
        upload(site, EDITOR, TestCertificates.ca("first").der());

        MvcTestResult second = upload(site, EDITOR, TestCertificates.ca("second").der());
        MvcTestResult third = upload(site, EDITOR, TestCertificates.ca("third").der());

        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(second).bodyJson().extractingPath("$.certificate.slot").isEqualTo("secondary");
        EnrollmentGroup group = azure.groups.get(EnrollmentGroup.idOf(site));
        assertThat(group.primary()).isNotNull();
        assertThat(group.secondary()).isNotNull();
        assertThat(third).hasStatus(HttpStatus.CONFLICT);
        assertThat(third).bodyJson().extractingPath("$.detail").asString().contains("remove one");
        assertThat(json(get("/api/v1/sites/" + site + "/certificates", READER)).size()).isEqualTo(2);
    }

    @Test
    void invalidCertificatesAreRejected() {
        createSite();
        TestCertificates.Generated ca = TestCertificates.ca("ca");
        Instant now = Instant.now();

        assertRejected(TestCertificates.device("device-1", ca).der(), "certificate authority certificate is required");
        assertRejected(ca.pemWithKey().getBytes(StandardCharsets.US_ASCII), "private key");
        assertRejected(TestCertificates.ca("old", now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(1))).der(), "expired");
        assertThat(upload(site, EDITOR, ca.der())).hasStatus(HttpStatus.CREATED);
        assertThat(upload(site, EDITOR, ca.pem().getBytes(StandardCharsets.US_ASCII))).hasStatus(HttpStatus.CONFLICT);
        assertThat(azure.certificates).hasSize(1);
    }

    @Test
    void provisioningServiceUnavailableChangesNothing() {
        createSite();
        azure.unavailable = true;

        MvcTestResult result = upload(site, EDITOR, TestCertificates.ca("ca").der());

        azure.unavailable = false;
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(json(get("/api/v1/sites/" + site + "/certificates", READER)).size()).isZero();
        assertThat(azure.groups).isEmpty();
        assertThat(azure.certificates).isEmpty();
    }

    @Test
    void recordsAreRepairedAfterAPartialFailure() {
        createSite();
        doThrow(new DataAccessResourceFailureException("database lost")).when(repository).insertCertificate(any());

        MvcTestResult result = upload(site, EDITOR, TestCertificates.ca("ca").der());

        doCallRealMethod().when(repository).insertCertificate(any());
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(azure.groups.get(EnrollmentGroup.idOf(site)).primary()).as("DPS was updated").isNotNull();
        assertThat(json(get("/api/v1/sites/" + site + "/certificates", READER)).size()).isZero();

        reconciliation.run();

        JsonNode listed = json(get("/api/v1/sites/" + site + "/certificates", READER));
        assertThat(listed.size()).isOne();
        assertThat(listed.get(0).get("addedBy").asString()).isEqualTo(Reconciliation.ACTOR);
        assertThat(audit(site)).extracting(e -> e.get("action").asString()).contains("RECONCILED_ADDED");
    }

    // --- Remove ---------------------------------------------------------------------------------------------

    @Test
    void removingThePrimaryOfTwoKeepsTheOtherWorking() {
        createSite();
        String primary = fingerprint(upload(site, EDITOR, TestCertificates.ca("old").der()));
        String secondary = fingerprint(upload(site, EDITOR, TestCertificates.ca("new").der()));
        String primaryName = azure.groups.get(EnrollmentGroup.idOf(site)).primary();

        MvcTestResult result = remove(site, primary);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.deviceOperation").isNull();
        EnrollmentGroup group = azure.groups.get(EnrollmentGroup.idOf(site));
        assertThat(group.enabled()).isTrue();
        assertThat(group.primary()).isEqualTo("site-" + site + "-" + secondary.substring(0, 12));
        assertThat(group.secondary()).isNull();
        assertThat(azure.certificates).doesNotContainKey(primaryName);
        JsonNode listed = json(get("/api/v1/sites/" + site + "/certificates", READER));
        assertThat(listed.size()).isOne();
        assertThat(listed.get(0).get("fingerprint").asString()).isEqualTo(secondary);
        assertThat(listed.get(0).get("slot").asString()).isEqualTo("primary");
        JsonNode removal = audit(site).get(0);
        assertThat(removal.get("action").asString()).isEqualTo("CERTIFICATE_REMOVED");
        assertThat(removal.get("actorId").asString()).isEqualTo(AccessTokens.USER_OID);
        assertThat(removal.get("actorName").asString()).isEqualTo("Ada Lovelace");
        assertThat(removal.get("fingerprint").asString()).isEqualTo(primary);
        assertThat(removal.get("occurredAt").asString()).isNotBlank();
    }

    @Test
    void lastCertificateRemovalDisablesTheSiteAndItsDevicesUntilTrustIsRestored() {
        createSite();
        var admin = ProvisioningDatabase.admin();
        for (String device : List.of(site + "-dev-1", site + "-dev-2", site + "-dev-3")) {
            admin.update("""
                    INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                    VALUES (?, ?, 'ruuvi-df5', now(), now())""", device, site);
            azure.devices.put(device, true);
        }
        azure.devices.put(site + "-dev-3", false); // disabled by an operator beforehand: must stay disabled
        String only = fingerprint(upload(site, EDITOR, TestCertificates.ca("only").der()));
        String onlyName = azure.groups.get(EnrollmentGroup.idOf(site)).primary();

        MvcTestResult removal = remove(site, only);

        assertThat(removal).hasStatus(HttpStatus.OK);
        assertThat(removal).bodyJson().extractingPath("$.deviceOperation.kind").isEqualTo("DISABLE_DEVICES");
        assertThat(removal).bodyJson().extractingPath("$.deviceOperation.status").isEqualTo("DONE");
        EnrollmentGroup group = azure.groups.get(EnrollmentGroup.idOf(site));
        assertThat(group.enabled()).as("no device can join").isFalse();
        assertThat(azure.certificates).containsKey(onlyName);
        assertThat(azure.devices).containsEntry(site + "-dev-1", false).containsEntry(site + "-dev-2", false);
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.sites WHERE site_id = ?", Long.class, site)).isOne();
        assertThat(json(get("/api/v1/sites/" + site + "/certificates", READER)).size()).isZero();

        MvcTestResult restored = upload(site, EDITOR, TestCertificates.ca("restored").der());

        assertThat(restored).hasStatus(HttpStatus.CREATED);
        assertThat(restored).bodyJson().extractingPath("$.deviceOperation.kind").isEqualTo("ENABLE_DEVICES");
        assertThat(azure.groups.get(EnrollmentGroup.idOf(site)).enabled()).isTrue();
        assertThat(azure.certificates).doesNotContainKey(onlyName);
        assertThat(azure.devices).containsEntry(site + "-dev-1", true).containsEntry(site + "-dev-2", true)
                .containsEntry(site + "-dev-3", false);
        assertThat(audit(site)).extracting(e -> e.get("action").asString())
                .containsSubsequence("DEVICES_ENABLED", "CERTIFICATE_ADDED", "DEVICES_DISABLED", "CERTIFICATE_REMOVED");
    }

    @Test
    void theLastCertificateCanBeTrustedAgainAfterItsRemoval() {
        createSite();
        ProvisioningDatabase.admin().update("""
                INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                VALUES (?, ?, 'ruuvi-df5', now(), now())""", site + "-dev-1", site);
        azure.devices.put(site + "-dev-1", true);
        byte[] ca = TestCertificates.ca("again").der();
        String fingerprint = fingerprint(upload(site, EDITOR, ca));
        String name = azure.groups.get(EnrollmentGroup.idOf(site)).primary();
        assertThat(remove(site, fingerprint)).hasStatus(HttpStatus.OK);

        // The disabled group still references the certificate, so it is still registered in DPS.
        MvcTestResult again = upload(site, EDITOR, ca);

        assertThat(again).hasStatus(HttpStatus.CREATED);
        assertThat(again).bodyJson().extractingPath("$.deviceOperation.kind").isEqualTo("ENABLE_DEVICES");
        EnrollmentGroup group = azure.groups.get(EnrollmentGroup.idOf(site));
        assertThat(group.enabled()).isTrue();
        assertThat(group.primary()).isEqualTo(name);
        assertThat(azure.certificates).containsKey(name);
        assertThat(azure.devices).containsEntry(site + "-dev-1", true);
    }

    @Test
    void unknownSiteOrCertificateIsNotFound() {
        assertThat(get("/api/v1/sites/nope-01/certificates", READER)).hasStatus(HttpStatus.NOT_FOUND);
        createSite();
        assertThat(remove(site, "b".repeat(64))).hasStatus(HttpStatus.NOT_FOUND);
    }

    // --- Listing, reconciliation ----------------------------------------------------------------------------

    @Test
    void bootstrapSitesAppearAfterReconciliationAddedByDeployment() {
        TestCertificates.Generated ca = TestCertificates.ca("CN=" + site + " demo site CA");
        azure.register(EnrollmentGroup.idOf(site), ca.pem());
        azure.put(EnrollmentGroup.create(site).withReferences(EnrollmentGroup.idOf(site), null));

        reconciliation.run();
        reconciliation.run();

        JsonNode listed = json(get("/api/v1/sites/" + site + "/certificates", READER));
        assertThat(listed.size()).isOne();
        assertThat(listed.get(0).get("slot").asString()).isEqualTo("primary");
        assertThat(listed.get(0).get("status").asString()).isEqualTo("valid");
        assertThat(listed.get(0).get("addedBy").asString()).isEqualTo("deployment");
        assertThat(audit(site)).extracting(e -> e.get("action").asString()).containsExactly("RECONCILED_ADDED");
    }

    @Test
    void certificateExpiringWithinThirtyDaysIsFlagged() {
        createSite();
        Instant now = Instant.now();
        upload(site, EDITOR, TestCertificates.ca("soon", now.minus(Duration.ofDays(300)), now.plus(Duration.ofDays(10))).der());

        JsonNode listed = json(get("/api/v1/site-certificates", READER));

        JsonNode mine = null;
        for (JsonNode c : listed) {
            if (site.equals(c.get("siteId").asString())) {
                mine = c;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("status").asString()).isEqualTo("expiring");
    }

    @Test
    void recordsOfCertificatesNoLongerInDpsAreRemoved() {
        createSite();
        upload(site, EDITOR, TestCertificates.ca("gone").der());
        azure.groups.clear();

        reconciliation.run();

        assertThat(json(get("/api/v1/sites/" + site + "/certificates", READER)).size()).isZero();
        assertThat(audit(site).get(0).get("action").asString()).isEqualTo("RECONCILED_REMOVED");
    }

    @Test
    void pendingDeviceOperationsAreRetried() {
        createSite();
        ProvisioningDatabase.admin().update("""
                INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                VALUES (?, ?, 'ruuvi-df5', now(), now())""", site + "-dev", site);
        azure.devices.put(site + "-dev", true);
        String only = fingerprint(upload(site, EDITOR, TestCertificates.ca("only").der()));
        // The removal counts the devices, then the device operation lists them: that listing fails once.
        doCallRealMethod().doThrow(new IllegalStateException("IoT Hub busy")).doCallRealMethod().when(repository).deviceIds(site);

        MvcTestResult removal = remove(site, only);

        assertThat(removal).bodyJson().extractingPath("$.deviceOperation.status").isEqualTo("PENDING");
        assertThat(azure.devices).containsEntry(site + "-dev", true);
        reconciliation.run();
        assertThat(azure.devices).containsEntry(site + "-dev", false);
    }

    // --- Helpers --------------------------------------------------------------------------------------------

    private void createSite() {
        assertThat(post("/api/v1/sites", EDITOR, Map.of("siteId", site, "displayName", "Site " + site))).hasStatus(HttpStatus.CREATED);
    }

    private void assertRejected(byte[] content, String reason) {
        MvcTestResult result = upload(site, EDITOR, content);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains(reason);
    }

    private MvcTestResult upload(String siteId, String token, byte[] content) {
        return mvc.post().uri("/api/v1/sites/" + siteId + "/certificates")
                .multipart().file(new MockMultipartFile("file", "ca.pem", "application/x-pem-file", content))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult remove(String siteId, String fingerprint) {
        return mvc.delete().uri("/api/v1/sites/" + siteId + "/certificates/" + fingerprint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + EDITOR).exchange();
    }

    private MvcTestResult post(String uri, String token, Object body) {
        return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)).exchange();
    }

    private MvcTestResult get(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private List<JsonNode> audit(String siteId) {
        JsonNode events = json(get("/api/v1/sites/" + siteId + "/audit", READER));
        List<JsonNode> list = new java.util.ArrayList<>();
        events.forEach(list::add);
        return list;
    }

    private static String fingerprint(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return json(result).get("certificate").get("fingerprint").asString();
    }

    private static JsonNode json(MvcTestResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

}
