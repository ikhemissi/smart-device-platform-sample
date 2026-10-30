package io.github.ikhemissi.sdp.deviceapi.anomalies;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.deviceapi.CatalogDatabase;
import io.github.ikhemissi.sdp.deviceapi.TestSecurity;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M7 task 3.1 — anomaly API (spec anomaly-detection: "Anomaly API"). */
@SpringBootTest(properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class AnomalyApiIT {

    private static final String READER = AccessTokens.withRoles("DeviceData.Read");
    private static final String EDITOR = AccessTokens.withRoles("DeviceData.Write");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.now();

    private static long confirmed;
    private static long toReview;
    private static long dismissed;
    private static long pending;
    private static long older;
    private static long lyon;

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
                INSERT INTO sdp.sites (site_id, display_name) VALUES ('an-paris-01', 'Paris (anomalies)'), ('an-lyon-01', 'Lyon (anomalies)')
                ON CONFLICT DO NOTHING""");
        for (String device : List.of("an-paris-1", "an-paris-2", "an-lyon-1")) {
            admin.update("""
                    INSERT INTO sdp.devices (device_id, site_id, display_name, last_payload_format, first_seen_at, last_seen_at)
                    VALUES (?, ?, 'Cold room', 'ruuvi-df5', now(), now()) ON CONFLICT DO NOTHING""",
                    device, device.startsWith("an-lyon") ? "an-lyon-01" : "an-paris-01");
        }
        confirmed = anomaly(admin, "an-paris-1", "an-paris-01", Duration.ofHours(1), "confirmed", "real_event", 0.91);
        toReview = anomaly(admin, "an-paris-1", "an-paris-01", Duration.ofHours(2), "to_review", "real_event", 0.55);
        dismissed = anomaly(admin, "an-paris-2", "an-paris-01", Duration.ofHours(3), "dismissed", "expected_pattern", 0.9);
        pending = anomaly(admin, "an-paris-2", "an-paris-01", Duration.ofHours(4), "pending", null, 0);
        older = anomaly(admin, "an-paris-2", "an-paris-01", Duration.ofDays(10), "confirmed", "sensor_fault", 0.97);
        lyon = anomaly(admin, "an-lyon-1", "an-lyon-01", Duration.ofHours(5), "confirmed", "sensor_fault", 0.8);
    }

    private static long anomaly(JdbcTemplate admin, String device, String site, Duration ago, String decision,
                                String classification, double p) {
        Instant start = NOW.minus(ago);
        String probabilities = classification == null ? null
                : "{\"real_event\": %s, \"sensor_fault\": 0.05, \"expected_pattern\": %s, \"cannot_tell\": 0.01}".formatted(
                        classification.equals("expected_pattern") ? 0.04 : classification.equals("real_event") ? p : 0.02,
                        classification.equals("expected_pattern") ? p : 0.02)
                        .replace("\"sensor_fault\": 0.05", "\"sensor_fault\": " + (classification.equals("sensor_fault") ? p : 0.05));
        return admin.queryForObject("""
                INSERT INTO sdp.anomalies (device_id, site_id, metric, kind, started_at, ended_at, observed_min, observed_max,
                                           observed_mean, observed_last, usual_value, spread, score, decision, classification,
                                           probabilities, confidence_threshold, decision_model, decision_model_version, state,
                                           explanation, explanation_model)
                VALUES (?, ?, 'temperature', 'deviation', ?, ?, 30, 35, 33, 35, 21, 0.2, 60, ?, ?, ?::jsonb, 0.7,
                        'microsoft-decision-1', '1', '{}', ?, 'gpt-6-luna')
                RETURNING id""", Long.class, device, site, Timestamp.from(start), Timestamp.from(start.plusSeconds(600)),
                decision, classification, probabilities, decision.equals("confirmed") ? "Explained." : null);
    }

    @Test
    void listsAnomaliesToActOnByDefault() {
        JsonNode anomalies = json(get("/api/v1/anomalies?siteId=an-paris-01", READER));

        assertThat(ids(anomalies)).containsExactly(confirmed, toReview);
        JsonNode first = anomalies.get(0);
        assertThat(first.get("status").asString()).isEqualTo("confirmed");
        assertThat(first.get("classification").asString()).isEqualTo("real_event");
        assertThat(first.get("confidence").asDouble()).isEqualTo(0.91);
        assertThat(first.get("probabilities").properties()).hasSize(4);
        assertThat(first.get("unit").asString()).isEqualTo("°C");
        assertThat(first.get("deviceName").asString()).isEqualTo("Cold room");
        assertThat(first.get("explanation").asString()).isEqualTo("Explained.");
        assertThat(first.get("confidenceThreshold").asDouble()).isEqualTo(0.7);
        assertThat(first.get("review").isNull()).isTrue();
    }

    @Test
    void filtersByStatusSiteDeviceAndPeriod() {
        assertThat(ids(json(get("/api/v1/anomalies?siteId=an-paris-01&status=dismissed,pending", READER))))
                .containsExactly(dismissed, pending);
        assertThat(ids(json(get("/api/v1/anomalies?siteId=an-paris-01&status=dismissed&status=confirmed", READER))))
                .containsExactly(confirmed, dismissed);
        assertThat(ids(json(get("/api/v1/anomalies?siteId=an-lyon-01", READER)))).containsExactly(lyon);
        assertThat(ids(json(get("/api/v1/anomalies?deviceId=an-paris-2&status=confirmed&from="
                + NOW.minus(Duration.ofDays(30)), READER)))).containsExactly(older);
        assertThat(ids(json(get("/api/v1/anomalies?siteId=an-paris-01&limit=1", READER)))).containsExactly(confirmed);
    }

    @Test
    void deviceAnomaliesIncludeReviewedOnes() {
        assertThat(ids(json(get("/api/v1/devices/an-paris-1/anomalies", READER)))).contains(confirmed, toReview);
        assertThat(get("/api/v1/devices/unknown-device/anomalies", READER)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidQueriesAreRejectedPerField() {
        assertThat(get("/api/v1/anomalies?status=bogus", READER)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.status[0]").asString().startsWith("must be among confirmed");
        assertThat(get("/api/v1/anomalies?from=" + NOW + "&to=" + NOW.minusSeconds(60), READER))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors.from[0]").isEqualTo("must be before to");
        assertThat(get("/api/v1/anomalies?limit=500", READER)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.limit[0]").isEqualTo("must be at most 200");
    }

    @Test
    void editorReviewsAnAnomalyAndCanChangeTheVerdict() {
        MvcTestResult first = review(lyon, "{\"verdict\": \"correct\", \"comment\": \"  Door left open  \"}", EDITOR);

        assertThat(first).hasStatus(HttpStatus.OK);
        JsonNode reviewed = json(first);
        assertThat(reviewed.get("status").asString()).isEqualTo("reviewed");
        assertThat(reviewed.get("decision").asString()).isEqualTo("confirmed");
        assertThat(reviewed.get("review").get("verdict").asString()).isEqualTo("correct");
        assertThat(reviewed.get("review").get("reviewedBy").asString()).isEqualTo("Ada Lovelace");
        assertThat(reviewed.get("review").get("comment").asString()).isEqualTo("Door left open");

        JsonNode again = json(review(lyon, "{\"verdict\": \"incorrect\"}", EDITOR));
        assertThat(again.get("review").get("verdict").asString()).isEqualTo("incorrect");
        assertThat(again.get("review").get("comment").isNull()).isTrue();
        assertThat(ids(json(get("/api/v1/anomalies?siteId=an-lyon-01", READER)))).as("no longer to act on").isEmpty();
        assertThat(ids(json(get("/api/v1/anomalies?siteId=an-lyon-01&status=reviewed", READER)))).containsExactly(lyon);
        assertThat(ids(json(get("/api/v1/devices/an-lyon-1/anomalies", READER)))).containsExactly(lyon);
    }

    @Test
    void readerCannotReview() {
        assertThat(review(toReview, "{\"verdict\": \"correct\"}", READER)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidReviewsAreRejected() {
        assertThat(review(toReview, "{\"verdict\": \"maybe\"}", EDITOR)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.verdict[0]").isEqualTo("must be correct or incorrect");
        assertThat(review(toReview, "{\"verdict\": \"correct\", \"comment\": \"" + "x".repeat(1001) + "\"}", EDITOR))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.comment[0]").isEqualTo("must be at most 1000 characters");
        assertThat(review(pending, "{\"verdict\": \"correct\"}", EDITOR)).hasStatus(HttpStatus.CONFLICT);
        assertThat(review(999_999_999L, "{\"verdict\": \"correct\"}", EDITOR)).hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult review(long id, String body, String token) {
        return mvc.post().uri("/api/v1/anomalies/" + id + "/review").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult get(String path, String token) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private static List<Long> ids(JsonNode anomalies) {
        return anomalies.valueStream().map(a -> a.get("id").asLong()).toList();
    }

    private static JsonNode json(MvcTestResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
