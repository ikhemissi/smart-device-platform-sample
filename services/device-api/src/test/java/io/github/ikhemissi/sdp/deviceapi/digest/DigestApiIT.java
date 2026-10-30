package io.github.ikhemissi.sdp.deviceapi.digest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.deviceapi.CatalogDatabase;
import io.github.ikhemissi.sdp.deviceapi.TestSecurity;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M8 task 3.1 — site digest API (spec site-digest: "Digest API"). */
@SpringBootTest(properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID,
        "sdp.digest.enabled=true",
        "sdp.digest.sites=dg-paris-01, dg-lyon-01"})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class DigestApiIT {

    private static final String READER = AccessTokens.withRoles("DeviceData.Read");
    private static final String EDITOR = AccessTokens.withRoles("DeviceData.Write");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvcTester mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        CatalogDatabase.register(registry);
    }

    @BeforeAll
    static void seed() {
        JdbcTemplate admin = CatalogDatabase.admin();
        admin.update("""
                INSERT INTO sdp.sites (site_id, display_name)
                VALUES ('dg-paris-01', 'Paris'), ('dg-lyon-01', 'Lyon'), ('dg-nice-01', 'Nice') ON CONFLICT DO NOTHING""");
        for (String generated : new String[] {"now() - interval '1 day'", "now()"}) {
            admin.update("""
                    INSERT INTO sdp.site_digests (site_id, trigger, period_from, period_to, comparison_from, generated_at,
                                                  figures, brief, brief_model, brief_generated_at)
                    VALUES ('dg-paris-01', 'scheduled', %1$s - interval '1 day', %1$s, %1$s - interval '8 days', %1$s,
                            '{"devices": {"total": 10, "reporting": 9}}', 'Brief at ' || %1$s, 'gpt-6-luna', %1$s)"""
                    .formatted(generated));
        }
    }

    @Test
    void readersGetTheLatestDigestWithItsFigures() {
        JsonNode body = json(get("/api/v1/sites/dg-paris-01/digest", READER));

        assertThat(body.get("enabled").asBoolean()).isTrue();
        JsonNode digest = body.get("digest");
        assertThat(digest.get("trigger").asString()).isEqualTo("scheduled");
        assertThat(digest.get("briefModel").asString()).isEqualTo("gpt-6-luna");
        assertThat(digest.get("figures").isObject()).as("figures are JSON, not a string").isTrue();
        assertThat(digest.get("figures").get("devices").get("reporting").asInt()).isEqualTo(9);
        assertThat(digest.get("generatedAt").asString()).isNotBlank();
        assertThat(body.get("pendingRequest").isNull()).isTrue();
    }

    @Test
    void aConfiguredSiteWithoutDigestYet() {
        JsonNode body = json(get("/api/v1/sites/dg-lyon-01/digest", READER));
        assertThat(body.get("enabled").asBoolean()).isTrue();
        assertThat(body.get("digest").isNull()).isTrue();
    }

    @Test
    void aSiteWithoutDigests() {
        JsonNode body = json(get("/api/v1/sites/dg-nice-01/digest", READER));
        assertThat(body.get("enabled").asBoolean()).isFalse();
        assertThat(body.get("digest").isNull()).isTrue();
        assertThat(post("dg-nice-01", EDITOR)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").asString().contains("has no digests");
    }

    @Test
    void unknownSite() {
        assertThat(get("/api/v1/sites/dg-nowhere/digest", READER)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post("dg-nowhere", EDITOR)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void editorsRequestADigestOnceUntilItIsServed() {
        MvcTestResult first = post("dg-lyon-01", EDITOR);
        assertThat(first).hasStatus(HttpStatus.ACCEPTED);
        JsonNode request = json(first);
        assertThat(request.get("requestedBy").asString()).isEqualTo("Ada Lovelace");

        JsonNode again = json(post("dg-lyon-01", EDITOR));
        assertThat(again.get("requestedAt").asString()).as("the pending request is returned")
                .isEqualTo(request.get("requestedAt").asString());
        assertThat(json(get("/api/v1/sites/dg-lyon-01/digest", READER)).get("pendingRequest").get("requestedBy").asString())
                .isEqualTo("Ada Lovelace");
        assertThat(CatalogDatabase.admin().queryForObject(
                "SELECT count(*) FROM sdp.site_digest_requests WHERE site_id = 'dg-lyon-01'", Integer.class)).isOne();
    }

    @Test
    void readersCannotRequestADigest() {
        assertThat(post("dg-paris-01", READER)).hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult post(String site, String token) {
        return mvc.post().uri("/api/v1/sites/" + site + "/digest/requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private MvcTestResult get(String path, String token) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private static JsonNode json(MvcTestResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
