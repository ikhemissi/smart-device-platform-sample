package io.github.ikhemissi.sdp.deviceapi.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.deviceapi.CatalogDatabase;
import io.github.ikhemissi.sdp.deviceapi.TestSecurity;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
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

/** M9 task 3.1 — maintenance API (spec predictive-maintenance: "Maintenance API", "Editor status"). */
@SpringBootTest(properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID,
        "sdp.maintenance.enabled=true",
        "sdp.maintenance.sites=mt-paris-01, mt-lyon-01"})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class MaintenanceApiIT {

    static final String READER = AccessTokens.withRoles("DeviceData.Read");
    static final String EDITOR = AccessTokens.withRoles("DeviceData.Write");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String BATTERY = """
            {"metric": "battery_voltage", "unit": "V", "level": 2.65, "slope_per_day": -0.05, "days_left": 3.0,
             "since": "2026-10-24T12:00:00+00:00", "cutoff": 2.5}""";
    static final String SIGNALS = """
            {"minutes_since_last": 0.4, "last_24h": {"messages": 2880, "median_interval_s": 30.0, "longest_gap_s": 31.0},
             "previous_7d": {"messages": 20160, "median_interval_s": 30.0, "longest_gap_s": 95.0},
             "longest_gap_over_usual_interval": 1.0, "rejected_messages_24h": 0}""";

    private static long likely;
    private static long planned;
    private static long lessLikely;
    private static long done;
    private static long resolved;
    private static long lyon;
    private static long otherSite;
    private static long toClose;

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
                INSERT INTO sdp.sites (site_id, display_name) VALUES ('mt-paris-01', 'Paris (maintenance)'),
                       ('mt-lyon-01', 'Lyon (maintenance)'), ('mt-nice-01', 'Nice (maintenance)') ON CONFLICT DO NOTHING""");
        for (String device : List.of("mt-paris-1", "mt-paris-2", "mt-paris-3", "mt-paris-4", "mt-paris-5", "mt-paris-6",
                "mt-paris-7", "mt-lyon-1", "mt-nice-1")) {
            String site = device.startsWith("mt-lyon") ? "mt-lyon-01" : device.startsWith("mt-nice") ? "mt-nice-01" : "mt-paris-01";
            admin.update("""
                    INSERT INTO sdp.devices (device_id, site_id, display_name, last_payload_format, first_seen_at, last_seen_at)
                    VALUES (?, ?, 'Cold room', 'ruuvi-df5', now(), now()) ON CONFLICT DO NOTHING""", device, site);
        }
        likely = item(admin, "mt-paris-1", "mt-paris-01", "open", 0.93, BATTERY, "Replace the battery.");
        planned = item(admin, "mt-paris-2", "mt-paris-01", "planned", 0.7, null, null);
        lessLikely = item(admin, "mt-paris-3", "mt-paris-01", "open", 0.55, null, null);
        done = item(admin, "mt-paris-4", "mt-paris-01", "done", 0.9, null, null);
        resolved = item(admin, "mt-paris-5", "mt-paris-01", "resolved", 0.2, null, null);
        lyon = item(admin, "mt-lyon-1", "mt-lyon-01", "open", 0.8, null, null);
        otherSite = item(admin, "mt-nice-1", "mt-nice-01", "open", 0.99, null, null);
        toClose = item(admin, "mt-paris-6", "mt-paris-01", "open", 0.6, null, null);
        admin.update("""
                INSERT INTO sdp.device_maintenance (device_id, site_id, assessed_at, battery, signals, probability, decision_model,
                                                    decision_model_version)
                VALUES ('mt-paris-1', 'mt-paris-01', now(), ?::jsonb, ?::jsonb, 0.93, 'microsoft-decision-1', '1'),
                       ('mt-paris-7', 'mt-paris-01', now(), NULL, ?::jsonb, 0.05, 'microsoft-decision-1', '1')
                ON CONFLICT DO NOTHING""", BATTERY, SIGNALS, SIGNALS);
    }

    static long item(JdbcTemplate admin, String device, String site, String status, double p, String battery, String order) {
        return admin.queryForObject("""
                INSERT INTO sdp.maintenance_items (device_id, site_id, status, probability, reasons, battery, work_order,
                                                   work_order_model, work_order_at, resolved_at, status_by, status_at)
                VALUES (?, ?, ?, ?, '[{"rule": "battery_days_left", "value": 3.0, "threshold": 7.0, "unit": "days"}]'::jsonb,
                        ?::jsonb, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE 'gpt-6-luna' END,
                        CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, CASE WHEN ?::text = 'resolved' THEN now() END,
                        CASE WHEN ?::text = 'done' THEN 'Grace Hopper' END,
                        CASE WHEN ?::text IN ('done', 'resolved') THEN now() END)
                RETURNING id""", Long.class, device, site, status, p, battery, order, order, order, status, status, status);
    }

    @Test
    void listsTheQueueMostLikelyFirst() {
        JsonNode items = json(get("/api/v1/maintenance?siteId=mt-paris-01", READER));

        assertThat(ids(items)).containsSubsequence(likely, planned, lessLikely).doesNotContain(done, resolved);
        JsonNode first = items.get(0);
        assertThat(first.get("id").asLong()).isEqualTo(likely);
        assertThat(first.get("deviceName").asString()).isEqualTo("Cold room");
        assertThat(first.get("probability").asDouble()).isEqualTo(0.93);
        assertThat(first.get("reasons").get(0).get("rule").asString()).isEqualTo("battery_days_left");
        assertThat(first.get("battery").get("days_left").asDouble()).as("battery is JSON").isEqualTo(3.0);
        assertThat(first.get("workOrder").asString()).isEqualTo("Replace the battery.");
        assertThat(items.get(1).get("battery").isNull()).isTrue();
    }

    @Test
    void filtersBySiteStatusAndLimit() {
        assertThat(ids(json(get("/api/v1/maintenance?siteId=mt-paris-01&status=done,resolved", READER))))
                .containsSubsequence(done, resolved).doesNotContain(likely, planned);
        assertThat(ids(json(get("/api/v1/maintenance?siteId=mt-paris-01&status=planned&status=done", READER))))
                .containsSubsequence(done, planned).doesNotContain(likely, resolved);
        assertThat(ids(json(get("/api/v1/maintenance?siteId=mt-lyon-01", READER)))).containsExactly(lyon);
        assertThat(ids(json(get("/api/v1/maintenance?siteId=mt-paris-01&limit=1", READER)))).containsExactly(likely);
    }

    @Test
    void sitesNotConfiguredAreLeftOut() {
        assertThat(ids(json(get("/api/v1/maintenance", READER)))).contains(likely, lyon).doesNotContain(otherSite);
        assertThat(ids(json(get("/api/v1/maintenance?siteId=mt-nice-01", READER)))).isEmpty();
        JsonNode nice = json(get("/api/v1/devices/mt-nice-1/maintenance", READER));
        assertThat(nice.get("enabled").asBoolean()).isFalse();
        assertThat(nice.get("assessment").isNull()).isTrue();
        assertThat(nice.get("item").isNull()).isTrue();
        assertThat(status(otherSite, "{\"status\": \"done\"}", EDITOR)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void deviceAssessmentWithItsItem() {
        JsonNode device = json(get("/api/v1/devices/mt-paris-1/maintenance", READER));

        assertThat(device.get("enabled").asBoolean()).isTrue();
        assertThat(device.get("assessment").get("probability").asDouble()).isEqualTo(0.93);
        assertThat(device.get("assessment").get("battery").get("level").asDouble()).isEqualTo(2.65);
        assertThat(device.get("assessment").get("signals").get("last_24h").get("messages").asInt()).isEqualTo(2880);
        assertThat(device.get("assessment").get("decisionModel").asString()).isEqualTo("microsoft-decision-1");
        assertThat(device.get("item").get("id").asLong()).isEqualTo(likely);
        assertThat(get("/api/v1/devices/unknown-device/maintenance", READER)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void deviceWithoutBatteryHasSignalsAndProbability() {
        JsonNode device = json(get("/api/v1/devices/mt-paris-7/maintenance", READER));

        assertThat(device.get("assessment").get("battery").isNull()).isTrue();
        assertThat(device.get("assessment").get("signals").get("minutes_since_last").asDouble()).isEqualTo(0.4);
        assertThat(device.get("assessment").get("probability").asDouble()).isEqualTo(0.05);
        assertThat(device.get("item").isNull()).isTrue();
    }

    @Test
    void editorPlansThenClosesAnItem() {
        MvcTestResult planning = status(toClose, "{\"status\": \"planned\", \"comment\": \"  Visit on Monday  \"}", EDITOR);
        assertThat(planning).hasStatus(HttpStatus.OK);
        JsonNode item = json(planning);
        assertThat(item.get("status").asString()).isEqualTo("planned");
        assertThat(item.get("statusBy").asString()).isEqualTo("Ada Lovelace");
        assertThat(item.get("statusComment").asString()).isEqualTo("Visit on Monday");
        assertThat(item.get("statusAt").asString()).isNotBlank();

        JsonNode closed = json(status(toClose, "{\"status\": \"done\", \"comment\": \"battery replaced\"}", EDITOR));
        assertThat(closed.get("status").asString()).isEqualTo("done");
        assertThat(closed.get("statusComment").asString()).isEqualTo("battery replaced");
        assertThat(ids(json(get("/api/v1/maintenance?siteId=mt-paris-01", READER)))).doesNotContain(toClose);
        assertThat(json(get("/api/v1/devices/mt-paris-6/maintenance", READER)).get("item").isNull()).isTrue();
        assertThat(status(toClose, "{\"status\": \"planned\"}", EDITOR)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void closedItemsCannotChange() {
        assertThat(status(done, "{\"status\": \"planned\"}", EDITOR)).hasStatus(HttpStatus.CONFLICT);
        assertThat(status(resolved, "{\"status\": \"done\"}", EDITOR)).hasStatus(HttpStatus.CONFLICT);
        assertThat(status(999_999_999L, "{\"status\": \"done\"}", EDITOR)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void readerCannotChangeAStatus() {
        assertThat(status(lessLikely, "{\"status\": \"planned\"}", READER)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidRequestsAreRejectedPerField() {
        assertThat(status(lessLikely, "{\"status\": \"resolved\"}", EDITOR)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.status[0]").isEqualTo("must be planned or done");
        assertThat(status(lessLikely, "{\"status\": \"planned\", \"comment\": \"" + "x".repeat(1001) + "\"}", EDITOR))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.comment[0]").isEqualTo("must be at most 1000 characters");
        assertThat(get("/api/v1/maintenance?status=bogus", READER)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.status[0]").asString().startsWith("must be among open");
        assertThat(get("/api/v1/maintenance?limit=500", READER)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.limit[0]").isEqualTo("must be at most 200");
    }

    private MvcTestResult status(long id, String body, String token) {
        return mvc.post().uri("/api/v1/maintenance/" + id + "/status").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult get(String path, String token) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private static List<Long> ids(JsonNode items) {
        return items.valueStream().map(a -> a.get("id").asLong()).toList();
    }

    static JsonNode json(MvcTestResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
