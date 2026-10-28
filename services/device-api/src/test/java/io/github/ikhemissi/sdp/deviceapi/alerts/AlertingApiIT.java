package io.github.ikhemissi.sdp.deviceapi.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.deviceapi.CatalogDatabase;
import io.github.ikhemissi.sdp.deviceapi.TestSecurity;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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

/** M5 tasks 3.2 and 3.3 — thresholds and alert history (spec threshold-alerting). */
@SpringBootTest(properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class AlertingApiIT {

    private static final String READER = AccessTokens.withRoles("DeviceData.Read");
    private static final String EDITOR = AccessTokens.withRoles("DeviceData.Write");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COLD_ROOM = """
            {"metric": "temperature", "min": 2, "max": 8, "recipients": ["Ops@Example.com", "cold-room@example.com"]}""";

    @Autowired
    private MockMvcTester mvc;

    private final JdbcTemplate admin = CatalogDatabase.admin();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        CatalogDatabase.register(registry);
    }

    @BeforeAll
    static void seed() {
        JdbcTemplate admin = CatalogDatabase.admin();
        admin.update("""
                INSERT INTO sdp.sites (site_id, display_name) VALUES ('al-paris-01', 'Paris (alerts)'), ('al-lyon-01', 'Lyon (alerts)')
                ON CONFLICT DO NOTHING""");
        for (String device : new String[] {"al-paris-1", "al-paris-2", "al-paris-3", "al-paris-4", "al-paris-5", "al-lyon-1"}) {
            admin.update("""
                    INSERT INTO sdp.devices (device_id, site_id, display_name, last_payload_format, first_seen_at, last_seen_at)
                    VALUES (?, ?, 'Cold room', 'ruuvi-df5', now(), now()) ON CONFLICT DO NOTHING""",
                    device, device.startsWith("al-lyon") ? "al-lyon-01" : "al-paris-01");
            admin.update("""
                    INSERT INTO sdp.device_metrics (device_id, metric, last_seen_at)
                    VALUES (?, 'temperature', now()), (?, 'humidity', now()) ON CONFLICT DO NOTHING""", device, device);
        }
    }

    @Test
    void editorCreatesAColdRoomThreshold() {
        MvcTestResult created = post("al-paris-1", COLD_ROOM, EDITOR);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created.getResponse().getHeader(HttpHeaders.ETAG)).isNotBlank();
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).startsWith("/api/v1/devices/al-paris-1/thresholds/");
        JsonNode listed = json(get("/api/v1/devices/al-paris-1/thresholds", READER));
        assertThat(listed).hasSize(1);
        JsonNode threshold = listed.get(0);
        assertThat(threshold.get("metric").asString()).isEqualTo("temperature");
        assertThat(threshold.get("unit").asString()).isEqualTo("°C");
        assertThat(threshold.get("min").decimalValue()).isEqualByComparingTo("2");
        assertThat(threshold.get("max").decimalValue()).isEqualByComparingTo("8");
        assertThat(threshold.get("recipients").valueStream().map(JsonNode::asString))
                .containsExactly("ops@example.com", "cold-room@example.com");
        assertThat(threshold.get("enabled").asBoolean()).isTrue();
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.alert_threshold_state WHERE threshold_id = ?::uuid",
                Integer.class, threshold.get("id").asString())).as("evaluation state created with the threshold").isOne();
    }

    @Test
    void invalidRangeIsRejected() {
        MvcTestResult result = post("al-paris-2", """
                {"metric": "temperature", "min": 8, "max": 2, "recipients": ["ops@example.com"]}""", EDITOR);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors.min[0]").isEqualTo("must be lower than max");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("min must be lower than max");
    }

    @Test
    void otherInvalidThresholdsAreRejectedPerField() {
        assertThat(post("al-paris-2", """
                {"metric": "temperature", "recipients": ["ops@example.com"]}""", EDITOR))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors.min[0]").isEqualTo("or max must be set");
        assertThat(post("al-paris-2", """
                {"metric": "temperature", "max": 8, "recipients": ["not-an-email"]}""", EDITOR))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors.recipients[0]")
                .isEqualTo("must be valid email addresses ('not-an-email')");
        assertThat(post("al-paris-2", """
                {"metric": "temperature", "max": 8, "recipients": []}""", EDITOR))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post("al-paris-2", """
                {"metric": "co2", "max": 800, "recipients": ["ops@example.com"]}""", EDITOR))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors.metric[0]")
                .isEqualTo("'co2' is not reported by device al-paris-2");
        assertThat(post("al-unknown", COLD_ROOM, EDITOR)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void readersSeeThresholdsButCannotChangeThem() {
        assertThat(post("al-paris-3", COLD_ROOM, READER)).hasStatus(HttpStatus.FORBIDDEN);
        JsonNode created = json(post("al-paris-3", COLD_ROOM, EDITOR));
        String path = "/api/v1/devices/al-paris-3/thresholds/" + created.get("id").asString();

        assertThat(mvc.put().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + READER)
                .header(HttpHeaders.IF_MATCH, created.get("etag").asString())
                .contentType(MediaType.APPLICATION_JSON).content(COLD_ROOM).exchange()).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.delete().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + READER).exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/devices/al-paris-3/thresholds", READER)).hasStatus(HttpStatus.OK);
    }

    @Test
    void updatesNeedTheCurrentEntityTag() {
        JsonNode created = json(post("al-paris-4", COLD_ROOM, EDITOR));
        String path = "/api/v1/devices/al-paris-4/thresholds/" + created.get("id").asString();
        String etag = created.get("etag").asString();

        assertThat(put(path, null, COLD_ROOM)).hasStatus(HttpStatus.PRECONDITION_REQUIRED);
        MvcTestResult updated = put(path, etag, """
                {"metric": "temperature", "max": 10, "recipients": ["ops@example.com"]}""");
        assertThat(updated).hasStatus(HttpStatus.OK);
        assertThat(updated).bodyJson().extractingPath("$.min").isNull();
        assertThat(put(path, etag, COLD_ROOM)).as("stale entity tag").hasStatus(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    void disablingOrDeletingAThresholdCancelsItsOpenAlertAndKeepsHistory() {
        JsonNode created = json(post("al-paris-5", COLD_ROOM, EDITOR));
        UUID id = UUID.fromString(created.get("id").asString());
        String path = "/api/v1/devices/al-paris-5/thresholds/" + id;
        long alert = openAlert(id, "al-paris-5", "al-paris-01");
        assertThat(json(get("/api/v1/devices/al-paris-5/thresholds", READER)).get(0).get("openAlertId").asLong()).isEqualTo(alert);

        MvcTestResult disabled = put(path, created.get("etag").asString(), """
                {"metric": "temperature", "min": 2, "max": 8, "recipients": ["ops@example.com"], "enabled": false}""");
        assertThat(disabled).hasStatus(HttpStatus.OK);
        assertThat(status(alert)).isEqualTo("CANCELLED");
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.alert_notifications WHERE alert_id = ?", Integer.class, alert))
                .as("no email").isZero();

        long second = openAlert(id, "al-paris-5", "al-paris-01");
        assertThat(mvc.delete().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + EDITOR).exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(status(second)).isEqualTo("CANCELLED");
        JsonNode history = json(get("/api/v1/devices/al-paris-5/alerts", READER));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("thresholdId").isNull()).as("threshold deleted, snapshot kept").isTrue();
        assertThat(history.get(0).get("thresholdMax").decimalValue()).isEqualByComparingTo("8");
        assertThat(json(get("/api/v1/devices/al-paris-5/thresholds", READER))).isEmpty();
    }

    @Test
    void openAlertsOfASite() {
        UUID paris = UUID.fromString(json(post("al-paris-2", """
                {"metric": "humidity", "max": 80, "recipients": ["ops@example.com"]}""", EDITOR)).get("id").asString());
        UUID lyon = UUID.fromString(json(post("al-lyon-1", """
                {"metric": "humidity", "max": 80, "recipients": ["ops@example.com"]}""", EDITOR)).get("id").asString());
        long closed = openAlert(paris, "al-paris-2", "al-paris-01");
        admin.update("UPDATE sdp.alert_events SET status = 'CLOSED', closed_at = now() WHERE id = ?", closed);
        long reopened = openAlert(paris, "al-paris-2", "al-paris-01");
        admin.update("""
                INSERT INTO sdp.alert_notifications (alert_id, kind, status, recipients, sent_at)
                VALUES (?, 'ALERT', 'SENT', ARRAY['ops@example.com'], now())""", reopened);
        openAlert(lyon, "al-lyon-1", "al-lyon-01");

        JsonNode alerts = json(get("/api/v1/alerts?status=open&siteId=al-paris-01", READER));

        assertThat(alerts.valueStream().map(a -> a.get("id").asLong())).containsExactly(reopened);
        JsonNode alert = alerts.get(0);
        assertThat(alert.get("status").asString()).isEqualTo("OPEN");
        assertThat(alert.get("deviceName").asString()).isEqualTo("Cold room");
        assertThat(alert.get("metric").asString()).isEqualTo("humidity");
        assertThat(alert.get("worstValue").decimalValue()).isEqualByComparingTo("12");
        assertThat(alert.get("notifications").get(0).get("kind").asString()).isEqualTo("ALERT");
        assertThat(alert.get("notifications").get(0).get("status").asString()).isEqualTo("SENT");
        assertThat(get("/api/v1/alerts?status=unknown", READER)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDeviceHasAtMostTwentyThresholds() {
        String device = "al-paris-3";
        int existing = json(get("/api/v1/devices/" + device + "/thresholds", READER)).size();
        for (int i = existing; i < 20; i++) {
            assertThat(post(device, """
                    {"metric": "temperature", "max": %d, "recipients": ["ops@example.com"]}""".formatted(30 + i), EDITOR))
                    .hasStatus(HttpStatus.CREATED);
        }
        assertThat(post(device, COLD_ROOM, EDITOR)).hasStatus(HttpStatus.CONFLICT);
    }

    private long openAlert(UUID threshold, String device, String site) {
        return admin.queryForObject("""
                INSERT INTO sdp.alert_events (threshold_id, device_id, site_id, metric, threshold_min, threshold_max, recipients,
                                              status, direction, opened_at, first_value, worst_value, last_value, last_breach_at)
                SELECT id, ?, ?, metric, min_value, max_value, recipients, 'OPEN', 'ABOVE', ?, 12, 12, 12, ?
                  FROM sdp.alert_thresholds WHERE id = ?
                RETURNING id""", Long.class, device, site, Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now()), threshold);
    }

    private String status(long alert) {
        return admin.queryForObject("SELECT status FROM sdp.alert_events WHERE id = ?", String.class, alert);
    }

    private MvcTestResult post(String device, String body, String token) {
        return mvc.post().uri("/api/v1/devices/" + device + "/thresholds").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult put(String path, String ifMatch, String body) {
        var request = mvc.put().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + EDITOR)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
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
