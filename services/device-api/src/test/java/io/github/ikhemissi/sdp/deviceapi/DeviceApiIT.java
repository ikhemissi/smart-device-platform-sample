package io.github.ikhemissi.sdp.deviceapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest(properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class DeviceApiIT {

    private static final String READER = AccessTokens.withRoles("DeviceData.Read");
    private static final String EDITOR = AccessTokens.withRoles("DeviceData.Write");
    private static final String NO_ROLE = AccessTokens.withRoles();
    private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

    @Autowired
    private MockMvcTester mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        CatalogDatabase.register(registry);
    }

    @BeforeAll
    static void seed() {
        var admin = CatalogDatabase.admin();
        admin.update("""
                INSERT INTO sdp.sites (site_id, display_name) VALUES
                  ('it-lyon-01', 'Lyon (IT)'), ('it-paris-01', 'Paris (IT)'), ('it-empty-01', 'Empty (IT)')
                ON CONFLICT DO NOTHING""");
        Instant now = Instant.now();
        insertDevice("it-lyon-ruuvi-1", "it-lyon-01", null, now.minusSeconds(60));
        insertDevice("it-lyon-bthome-1", "it-lyon-01", "Ruuvi kitchen", now.minusSeconds(120));
        insertDevice("it-lyon-lpp-1", "it-lyon-01", "Cellar", now.minus(Duration.ofMinutes(20)));
        insertDevice("it-paris-ruuvi-1", "it-paris-01", null, now.minusSeconds(30));
        insertDevice("it-edit-1", "it-paris-01", null, now.minusSeconds(30));
        insertDevice("it-edit-2", "it-paris-01", null, now.minusSeconds(30));
        insertDevice("it-edit-3", "it-paris-01", null, now.minusSeconds(30));
    }

    private static void insertDevice(String id, String site, String name, Instant lastSeen) {
        CatalogDatabase.admin().update("""
                INSERT INTO sdp.devices (device_id, site_id, display_name, last_payload_format, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, 'ruuvi-df5', ?, ?) ON CONFLICT DO NOTHING""",
                id, site, name, Timestamp.from(lastSeen.minus(Duration.ofDays(1))), Timestamp.from(lastSeen));
    }

    // --- Access ---------------------------------------------------------------------------------------------

    @Test
    void directCallWithoutTokenIsUnauthorized() {
        assertThat(mvc.get().uri("/api/v1/devices").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthProbesArePublic() {
        assertThat(mvc.get().uri("/actuator/health/liveness").exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    void meReportsPermissions() {
        MvcTestResult noRole = get("/api/v1/me", NO_ROLE);
        assertThat(noRole).hasStatus(HttpStatus.OK);
        assertThat(noRole).bodyJson().extractingPath("$.canRead").isEqualTo(false);

        MvcTestResult reader = get("/api/v1/me", READER);
        assertThat(reader).bodyJson().extractingPath("$.canRead").isEqualTo(true);
        assertThat(reader).bodyJson().extractingPath("$.canWrite").isEqualTo(false);

        MvcTestResult editor = get("/api/v1/me", EDITOR);
        assertThat(editor).bodyJson().extractingPath("$.canRead").isEqualTo(true);
        assertThat(editor).bodyJson().extractingPath("$.canWrite").isEqualTo(true);
        assertThat(editor).bodyJson().extractingPath("$.objectId").isEqualTo(AccessTokens.USER_OID);
    }

    @Test
    void userWithoutRoleGetsNoData() {
        assertThat(get("/api/v1/devices", NO_ROLE)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/sites", NO_ROLE)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/devices/it-lyon-ruuvi-1", NO_ROLE)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void writerCanRead() {
        assertThat(get("/api/v1/devices", EDITOR)).hasStatus(HttpStatus.OK);
    }

    @Test
    void readerCannotEdit() {
        String etag = get("/api/v1/devices/it-edit-3", READER).getResponse().getHeader(HttpHeaders.ETAG);

        MvcTestResult result = patch("/api/v1/devices/it-edit-3", READER, etag, "{\"displayName\":\"Hacked\"}");

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/devices/it-edit-3", READER)).bodyJson().extractingPath("$.displayName").isNull();
    }

    // --- List ---------------------------------------------------------------------------------------------

    @Test
    void filterBySiteAndSearchCaseInsensitively() {
        MvcTestResult result = get("/api/v1/devices?siteId=it-lyon-01&search=RUUVI", READER);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.items[*].deviceId").asArray()
                .containsExactlyInAnyOrder("it-lyon-ruuvi-1", "it-lyon-bthome-1");
        assertThat(result).bodyJson().extractingPath("$.total").isEqualTo(2);
    }

    @Test
    void statusIsDerivedFromLastSeen() {
        MvcTestResult silent = get("/api/v1/devices?siteId=it-lyon-01&status=silent", READER);
        assertThat(silent).bodyJson().extractingPath("$.items[*].deviceId").asArray().containsExactly("it-lyon-lpp-1");
        assertThat(silent).bodyJson().extractingPath("$.items[0].status").isEqualTo("silent");

        MvcTestResult reporting = get("/api/v1/devices?siteId=it-lyon-01&status=reporting", READER);
        assertThat(reporting).bodyJson().extractingPath("$.total").isEqualTo(2);
        assertThat(reporting).bodyJson().extractingPath("$.items[0].status").isEqualTo("reporting");
    }

    @Test
    void sortAndPage() {
        MvcTestResult first = get("/api/v1/devices?siteId=it-lyon-01&sort=lastSeen&order=desc&pageSize=2", READER);
        assertThat(first).bodyJson().extractingPath("$.items[*].deviceId").asArray()
                .containsExactly("it-lyon-ruuvi-1", "it-lyon-bthome-1");
        assertThat(first).bodyJson().extractingPath("$.total").isEqualTo(3);

        MvcTestResult second = get("/api/v1/devices?siteId=it-lyon-01&sort=lastSeen&order=desc&pageSize=2&page=2", READER);
        assertThat(second).bodyJson().extractingPath("$.items[*].deviceId").asArray().containsExactly("it-lyon-lpp-1");
        assertThat(second).bodyJson().extractingPath("$.page").isEqualTo(2);
    }

    @Test
    void pageSizeIsLimited() {
        MvcTestResult result = get("/api/v1/devices?pageSize=500", READER);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Bad Request");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(400);
        assertThat(result).bodyJson().extractingPath("$.errors.pageSize[0]").isEqualTo("must be at most 100");
    }

    @Test
    void invalidParametersAreReportedPerField() {
        MvcTestResult result = get("/api/v1/devices?status=offline&sort=name&page=abc", READER);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors.page").isNotNull();
    }

    // --- Get / edit -----------------------------------------------------------------------------------------

    @Test
    void unknownDeviceIsNotFound() {
        MvcTestResult result = get("/api/v1/devices/does-not-exist", READER);

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentTypeCompatibleWith("application/problem+json");
    }

    @Test
    void editorEditsDeviceInformation() {
        MvcTestResult before = get("/api/v1/devices/it-edit-1", READER);
        String etag = before.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();

        MvcTestResult result = patch("/api/v1/devices/it-edit-1", EDITOR, etag, """
                {"displayName":"  Meeting room  ","description":"Second floor",
                 "location":{"latitude":48.85,"longitude":2.35,"label":"Paris HQ"}}""");

        assertThat(result).hasStatus(HttpStatus.OK);
        String newTag = result.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(newTag).isNotEqualTo(etag);
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Meeting room");
        assertThat(result).bodyJson().extractingPath("$.location.label").isEqualTo("Paris HQ");
        assertThat(result).bodyJson().extractingPath("$.updatedBy.objectId").isEqualTo(AccessTokens.USER_OID);
        assertThat(result).bodyJson().extractingPath("$.updatedBy.displayName").isEqualTo("Ada Lovelace");
        assertThat(result).bodyJson().extractingPath("$.updatedAt").isNotNull();

        MvcTestResult after = get("/api/v1/devices/it-edit-1", READER);
        assertThat(after.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(newTag);
        assertThat(after).bodyJson().extractingPath("$.description").isEqualTo("Second floor");

        // Merge patch: only the label changes, null clears the description.
        MvcTestResult partial = patch("/api/v1/devices/it-edit-1", EDITOR, newTag,
                "{\"description\":null,\"location\":{\"label\":\"HQ\"}}");
        assertThat(partial).hasStatus(HttpStatus.OK);
        assertThat(partial).bodyJson().extractingPath("$.description").isNull();
        assertThat(partial).bodyJson().extractingPath("$.location.latitude").isEqualTo(48.85);
        assertThat(partial).bodyJson().extractingPath("$.displayName").isEqualTo("Meeting room");
    }

    @Test
    void telemetryDoesNotInvalidateTheEntityTag() {
        String etag = get("/api/v1/devices/it-edit-2", READER).getResponse().getHeader(HttpHeaders.ETAG);
        CatalogDatabase.admin().update("UPDATE sdp.devices SET last_seen_at = now() WHERE device_id = 'it-edit-2'");

        assertThat(get("/api/v1/devices/it-edit-2", READER).getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(etag);
    }

    @Test
    void outdatedEntityTagIsRejected() {
        String etag = get("/api/v1/devices/it-edit-2", READER).getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(patch("/api/v1/devices/it-edit-2", EDITOR, etag, "{\"displayName\":\"First\"}")).hasStatus(HttpStatus.OK);

        MvcTestResult stale = patch("/api/v1/devices/it-edit-2", EDITOR, etag, "{\"displayName\":\"Second\"}");

        assertThat(stale).hasStatus(HttpStatus.PRECONDITION_FAILED).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(get("/api/v1/devices/it-edit-2", READER)).bodyJson().extractingPath("$.displayName").isEqualTo("First");
    }

    @Test
    void missingIfMatchIsRejected() {
        assertThat(patch("/api/v1/devices/it-edit-3", EDITOR, null, "{\"displayName\":\"x\"}"))
                .hasStatus(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void nonEditableFieldsAreRejected() {
        String etag = get("/api/v1/devices/it-edit-3", READER).getResponse().getHeader(HttpHeaders.ETAG);

        MvcTestResult result = patch("/api/v1/devices/it-edit-3", EDITOR, etag, "{\"siteId\":\"it-lyon-01\",\"displayName\":\"x\"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors.siteId[0]").isEqualTo("is not editable");
        assertThat(get("/api/v1/devices/it-edit-3", READER)).bodyJson().extractingPath("$.siteId").isEqualTo("it-paris-01");
    }

    @Test
    void invalidValuesAreRejected() {
        String etag = get("/api/v1/devices/it-edit-3", READER).getResponse().getHeader(HttpHeaders.ETAG);

        MvcTestResult result = patch("/api/v1/devices/it-edit-3", EDITOR, etag,
                "{\"displayName\":\"" + "x".repeat(101) + "\",\"location\":{\"latitude\":91,\"longitude\":0}}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors.displayName[0]").isEqualTo("must be at most 100 characters");
        assertThat(result).bodyJson().extractingPath("$.errors['location.latitude'][0]").isEqualTo("must be between -90 and 90");
    }

    // --- Sites ----------------------------------------------------------------------------------------------

    @Test
    void sitesWithDeviceCounts() {
        MvcTestResult result = get("/api/v1/sites", READER);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$[?(@.siteId == 'it-lyon-01')].deviceCount").asArray().containsExactly(3);
        assertThat(result).bodyJson().extractingPath("$[?(@.siteId == 'it-empty-01')].deviceCount").asArray().containsExactly(0);
    }

    // --- Performance ---------------------------------------------------------------------------------------

    @Test
    void listingAThousandDevicesIsFast() {
        var admin = CatalogDatabase.admin();
        admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('it-perf-01', 'Perf') ON CONFLICT DO NOTHING");
        admin.update("""
                INSERT INTO sdp.devices (device_id, site_id, display_name, last_payload_format, first_seen_at, last_seen_at)
                SELECT 'it-perf-' || g, 'it-perf-01', CASE WHEN g % 3 = 0 THEN 'Sensor ' || g END, 'ruuvi-df5',
                       now() - interval '2 days', now() - (g || ' seconds')::interval
                  FROM generate_series(1, 1000) g ON CONFLICT DO NOTHING""");
        String[] queries = {
                "/api/v1/devices?siteId=it-perf-01&pageSize=100",
                "/api/v1/devices?search=sensor 9&sort=lastSeen&order=desc",
                "/api/v1/devices?status=silent&sort=displayName&page=3",
                "/api/v1/devices?siteId=it-perf-01&sort=firstSeen&page=10&pageSize=50"};
        for (String query : queries) {
            assertThat(get(query, READER)).hasStatus(HttpStatus.OK);
        }
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long start = System.nanoTime();
            get(queries[i % queries.length], READER);
            durations.add(Duration.ofNanos(System.nanoTime() - start).toMillis());
        }
        durations.sort(null);
        long p95 = durations.get((int) Math.ceil(durations.size() * 0.95) - 1);
        assertThat(p95).as("p95 in ms").isLessThan(500);
    }

    private MvcTestResult get(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult patch(String uri, String token, String etag, String body) {
        var request = mvc.patch().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MERGE_PATCH).content(body);
        if (etag != null) {
            request = request.header(HttpHeaders.IF_MATCH, etag);
        }
        return request.exchange();
    }
}
