package io.github.ikhemissi.sdp.deviceapi.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ikhemissi.sdp.deviceapi.CatalogDatabase;
import io.github.ikhemissi.sdp.deviceapi.TestSecurity;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Spec fleet-analytics scenarios (M6 tasks 2.1 and 2.2), as the device API's least-privilege database principal. */
@SpringBootTest(properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class FleetApiIT {

    private static final String READER = AccessTokens.withRoles("DeviceData.Read");
    private static final Instant HOUR = Instant.now().truncatedTo(ChronoUnit.HOURS);
    /** Measurements every 10 minutes over the 8 days before HOUR. */
    private static final Duration STEP = Duration.ofMinutes(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvcTester mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        CatalogDatabase.register(registry);
    }

    @BeforeAll
    static void seed() {
        var admin = CatalogDatabase.admin();
        admin.queryForList("""
                SELECT partman.create_partition_time('sdp.measurements',
                       ARRAY(SELECT generate_series(date_trunc('day', now() - interval '9 days'), date_trunc('day', now()), interval '1 day')))""");
        for (String site : List.of("fleet-a", "fleet-b", "fleet-empty")) {
            admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES (?, ?) ON CONFLICT DO NOTHING", site, site.toUpperCase());
        }
        device("fleet-a-001", "fleet-a", "now()");
        device("fleet-a-002", "fleet-a", "now() - interval '2 hours'");
        device("fleet-a-003", "fleet-a", "now() - interval '3 days'");
        device("fleet-b-001", "fleet-b", "now()");
        // Humidity per device (40, 60, 70); battery voltage declining by 0.1 mV per measurement on fleet-a; no battery on fleet-b.
        series("fleet-a-001", "fleet-a", "'humidity', 40, 'battery_voltage', 3.0 - n * 0.0001");
        series("fleet-a-002", "fleet-a", "'humidity', 60, 'battery_voltage', 2.9 - n * 0.0001");
        series("fleet-b-001", "fleet-b", "'humidity', 70");
        admin.update("UPDATE sdp.metric_rollup_state SET rolled_until = NULL");
    }

    private static void device(String id, String site, String lastSeen) {
        CatalogDatabase.admin().update("""
                INSERT INTO sdp.devices (device_id, site_id, display_name, last_payload_format, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, 'ruuvi-df5', now() - interval '9 days', %s) ON CONFLICT DO NOTHING""".formatted(lastSeen),
                id, site, "Sensor " + id);
    }

    private static void series(String device, String site, String metrics) {
        CatalogDatabase.admin().update("""
                INSERT INTO sdp.measurements (device_id, site_id, measured_at, received_at, message_key, payload_format, raw_payload, metrics)
                SELECT ?, ?, t, t, 'k-' || n, 'ruuvi-df5', '\\x05'::bytea, jsonb_build_object(%s)
                  FROM generate_series(1, ?) AS n, LATERAL (SELECT ?::timestamptz - interval '192 hours' + n * ?::interval AS t) ts
                 WHERE t < ?::timestamptz""".formatted(metrics),
                device, site, (int) (Duration.ofDays(8).toMinutes() / STEP.toMinutes()), Timestamp.from(HOUR),
                STEP.toMinutes() + " minutes", Timestamp.from(HOUR));
    }

    @Test
    void compareHumidityBetweenSites() {
        JsonNode body = json(get(summary("humidity", "site", null)));

        assertThat(body.get("metric").asString()).isEqualTo("humidity");
        assertThat(body.get("unit").asString()).isEqualTo("%");
        JsonNode a = group(body, "fleet-a", null);
        assertThat(a.get("min").asDouble()).isEqualTo(40);
        assertThat(a.get("max").asDouble()).isEqualTo(60);
        assertThat(a.get("avg").asDouble()).isCloseTo(50, within(0.01));
        assertThat(a.get("devices").asInt()).isEqualTo(2);
        assertThat(a.get("samples").asLong()).isEqualTo(2 * samplesIn7Days());
        assertThat(a.has("deviceId")).isFalse();
        JsonNode b = group(body, "fleet-b", null);
        assertThat(b.get("avg").asDouble()).isEqualTo(70);
        assertThat(b.get("devices").asInt()).isEqualTo(1);
        assertThat(group(body, "fleet-empty", null)).as("sites without data are omitted").isNull();
    }

    @Test
    void batteryOverviewOfASite() {
        JsonNode body = json(get(summary("battery_voltage", "device", "fleet-a")));

        assertThat(body.get("siteId").asString()).isEqualTo("fleet-a");
        assertThat(body.get("groups").size()).isEqualTo(2);
        JsonNode device = group(body, "fleet-a", "fleet-a-001");
        assertThat(device.get("devices").asInt()).isEqualTo(1);
        double first = device.get("first").get("value").asDouble();
        double last = device.get("last").get("value").asDouble();
        assertThat(first - last).as("decline over 7 days").isCloseTo(0.0001 * (samplesIn7Days() - 1), within(1e-9));
        assertThat(Instant.parse(device.get("last").get("at").asString())).isBefore(HOUR).isAfter(HOUR.minus(STEP.multipliedBy(2)));
        assertThat(json(get(summary("battery_voltage", "device", "fleet-b"))).get("groups").size()).isZero();
    }

    @Test
    void rollupsGiveTheSameSummaryAsRawMeasurements() {
        String uri = "/api/v1/fleet/metric-summary?metric=humidity&groupBy=device&from=" + HOUR.minus(Duration.ofDays(7)).minusSeconds(900)
                + "&to=" + HOUR.minusSeconds(300);
        List<JsonNode> raw = fleetGroups(json(get(uri)));
        var admin = CatalogDatabase.admin();
        try {
            // Same aggregation as the maintenance job (db-migrator MetricRollups), up to two hours before HOUR.
            admin.update("""
                    INSERT INTO sdp.metric_rollups_hourly (metric, hour, device_id, site_id, min_value, max_value, sum_value,
                                                           sample_count, first_at, first_value, last_at, last_value)
                    SELECT e.key, date_trunc('hour', m.measured_at, 'UTC'), m.device_id, max(m.site_id),
                           min(e.v), max(e.v), sum(e.v), count(*),
                           min(m.measured_at), (array_agg(e.v ORDER BY m.measured_at))[1],
                           max(m.measured_at), (array_agg(e.v ORDER BY m.measured_at DESC))[1]
                      FROM sdp.measurements m
                     CROSS JOIN LATERAL (SELECT key, value::text::float8 AS v
                                           FROM jsonb_each(m.metrics) WHERE jsonb_typeof(value) = 'number') e
                     WHERE m.measured_at < ?::timestamptz AND m.device_id LIKE 'fleet-%'
                     GROUP BY e.key, date_trunc('hour', m.measured_at, 'UTC'), m.device_id""",
                    Timestamp.from(HOUR.minus(Duration.ofHours(2))));
            admin.update("UPDATE sdp.metric_rollup_state SET rolled_until = ?", Timestamp.from(HOUR.minus(Duration.ofHours(2))));

            assertThat(fleetGroups(json(get(uri)))).isEqualTo(raw);
        } finally {
            admin.update("DELETE FROM sdp.metric_rollups_hourly WHERE device_id LIKE 'fleet-%'");
            admin.update("UPDATE sdp.metric_rollup_state SET rolled_until = NULL");
        }
    }

    @Test
    void invalidMetricListsValidNames() {
        MvcTestResult result = get(summary("humidty", "site", null));
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors.metric[0]").asString()
                .startsWith("must be one of ").contains("humidity", "battery_voltage", "temperature");
    }

    @Test
    void invalidRangeGroupingAndSite() {
        assertThat(get("/api/v1/fleet/metric-summary?metric=humidity&from=" + HOUR.minus(Duration.ofDays(32)) + "&to=" + HOUR))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors.to[0]").isEqualTo("the range must not exceed 31 days");
        assertThat(get(summary("humidity", "region", null)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors.groupBy[0]").isEqualTo("must be site or device");
        assertThat(get(summary("humidity", "site", "nowhere"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/fleet/status?siteId=nowhere")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void silentDevicesOfASite() {
        JsonNode body = json(get("/api/v1/fleet/status?siteId=fleet-a"));

        assertThat(body.size()).isOne();
        JsonNode site = body.get(0);
        assertThat(site.get("siteId").asString()).isEqualTo("fleet-a");
        assertThat(site.get("displayName").asString()).isEqualTo("FLEET-A");
        assertThat(site.get("devices").asLong()).isEqualTo(3);
        assertThat(site.get("reporting").asLong()).isEqualTo(1);
        assertThat(site.get("silent").asLong()).isEqualTo(2);
        List<String> silent = new ArrayList<>();
        site.get("silentDevices").forEach(d -> silent.add(d.get("deviceId").asString()));
        assertThat(silent).as("most recently seen first").containsExactly("fleet-a-002", "fleet-a-003");
        assertThat(site.get("silentDevices").get(0).get("lastSeenAt").isString()).isTrue();
    }

    @Test
    void statusOfEverySite() {
        List<String> sites = new ArrayList<>();
        json(get("/api/v1/fleet/status")).forEach(s -> sites.add(s.get("siteId").asString()));
        assertThat(sites).contains("fleet-a", "fleet-b", "fleet-empty").isSorted();
    }

    @Test
    void requiresTheReadRole() {
        assertThat(mvc.get().uri("/api/v1/fleet/status").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/fleet/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + AccessTokens.withRoles()).exchange()).hasStatus(HttpStatus.FORBIDDEN);
    }

    private static long samplesIn7Days() {
        return Duration.ofDays(7).toMinutes() / STEP.toMinutes();
    }

    private static String summary(String metric, String groupBy, String siteId) {
        return "/api/v1/fleet/metric-summary?metric=" + metric + "&groupBy=" + groupBy + "&from=" + HOUR.minus(Duration.ofDays(7))
                + "&to=" + HOUR + (siteId == null ? "" : "&siteId=" + siteId);
    }

    // Other test classes share the database; only the fleet devices are rolled up here.
    private static List<JsonNode> fleetGroups(JsonNode body) {
        List<JsonNode> groups = new ArrayList<>();
        body.get("groups").forEach(g -> {
            if (g.get("siteId").asString().startsWith("fleet-")) {
                groups.add(g);
            }
        });
        return groups;
    }

    private static JsonNode group(JsonNode body, String siteId, String deviceId) {
        for (JsonNode g : body.get("groups")) {
            if (g.get("siteId").asString().equals(siteId) && (deviceId == null || g.get("deviceId").asString().equals(deviceId))) {
                return g;
            }
        }
        return null;
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + READER).exchange();
    }

    private static JsonNode json(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.OK);
        try {
            return JSON.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
