package io.github.ikhemissi.sdp.deviceapi.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.deviceapi.CatalogDatabase;
import io.github.ikhemissi.sdp.deviceapi.TestSecurity;
import io.github.ikhemissi.sdp.testsupport.AccessTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

/** Spec telemetry-history scenarios, connected as the device API's least-privilege database principal. */
@SpringBootTest(properties = {
        "sdp.security.tenant-id=" + AccessTokens.TENANT,
        "sdp.security.audiences=" + AccessTokens.CLIENT_ID})
@AutoConfigureMockMvc
@Import(TestSecurity.class)
class HistoryApiIT {

    private static final String READER = AccessTokens.withRoles("DeviceData.Read");
    private static final String LATEST = "hist-01-latest-001";
    private static final String RANGE = "hist-01-range-001";
    private static final String MONTH = "hist-01-month-001";
    private static final String RUUVI = "hist-01-ruuvi5-001";
    /** Fixed reference time, minute-aligned, so expected counts are exact. */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.HOURS);
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
                       ARRAY(SELECT generate_series(date_trunc('day', now() - interval '32 days'), date_trunc('day', now()), interval '1 day')))""");
        admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('hist-01', 'History') ON CONFLICT DO NOTHING");
        for (String device : List.of(LATEST, RANGE, MONTH, RUUVI)) {
            admin.update("""
                    INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                    VALUES (?, 'hist-01', 'ruuvi-df5', now() - interval '31 days', now()) ON CONFLICT DO NOTHING""", device);
        }
        // 500 measurements one minute apart, and 1,200 over the 10 hours before NOW (30 s apart).
        insertSeries(LATEST, "interval '500 minutes'", "interval '1 minute'");
        insertSeries(RANGE, "interval '10 hours'", "interval '30 seconds'");
        // 30 days every 30 s.
        insertSeries(MONTH, "interval '720 hours'", "interval '30 seconds'");
        for (String metric : List.of("temperature", "humidity", "pressure", "acceleration_x", "acceleration_y", "acceleration_z",
                "battery_voltage", "tx_power", "movement_counter", "measurement_sequence")) {
            admin.update("INSERT INTO sdp.device_metrics (device_id, metric, last_seen_at) VALUES (?, ?, now())", RUUVI, metric);
        }
        admin.update("INSERT INTO sdp.device_metrics (device_id, metric, last_seen_at) VALUES (?, 'co2', now() - interval '40 days')", RUUVI);
    }

    private static void insertSeries(String device, String span, String step) {
        CatalogDatabase.admin().update("""
                INSERT INTO sdp.measurements (device_id, site_id, measured_at, received_at, message_key, payload_format, raw_payload, metrics)
                SELECT ?, 'hist-01', t, t, 'k-' || extract(epoch FROM t)::bigint, 'ruuvi-df5', '\\x05'::bytea,
                       jsonb_build_object('temperature', round((20 + 5 * sin(extract(epoch FROM t) / 13751.0))::numeric, 2),
                                          'humidity', 45)
                  FROM generate_series(?::timestamptz - %s, ?::timestamptz - %s, %s) AS t""".formatted(span, step, step),
                device, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    // --- Latest ---------------------------------------------------------------------------------------------

    @Test
    void latestTwentyInDescendingOrder() {
        JsonNode body = json(get("/api/v1/devices/" + LATEST + "/measurements/latest"));

        assertThat(body.size()).isEqualTo(20);
        assertThat(Instant.parse(body.get(0).get("measuredAt").asString())).isEqualTo(NOW.minusSeconds(60));
        for (int i = 1; i < body.size(); i++) {
            assertThat(Instant.parse(body.get(i).get("measuredAt").asString()))
                    .isBefore(Instant.parse(body.get(i - 1).get("measuredAt").asString()));
        }
        assertThat(body.get(0).get("payloadFormat").asString()).isEqualTo("ruuvi-df5");
        assertThat(body.get(0).get("metrics").has("temperature")).isTrue();
        assertThat(body.get(0).get("attributes").isObject()).isTrue();
    }

    @Test
    void latestCountIsLimitedTo100() {
        assertThat(json(get("/api/v1/devices/" + LATEST + "/measurements/latest?count=100")).size()).isEqualTo(100);
        MvcTestResult tooMany = get("/api/v1/devices/" + LATEST + "/measurements/latest?count=101");
        assertThat(tooMany).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(tooMany).bodyJson().extractingPath("$.errors.count[0]").isEqualTo("must be at most 100");
    }

    @Test
    void unknownDeviceIsNotFoundOnEveryHistoryEndpoint() {
        for (String path : List.of("/measurements/latest", "/metrics", "/series?metrics=temperature&from=" + NOW.minusSeconds(3600),
                "/measurements?from=" + NOW.minusSeconds(3600) + "&to=" + NOW)) {
            MvcTestResult result = get("/api/v1/devices/does-not-exist" + path);
            assertThat(result).as(path).hasStatus(HttpStatus.NOT_FOUND).hasContentTypeCompatibleWith("application/problem+json");
        }
    }

    // --- Range ----------------------------------------------------------------------------------------------

    @Test
    void pagingThroughARangeHasNoDuplicatesOrGaps() {
        String base = "/api/v1/devices/" + RANGE + "/measurements?from=" + NOW.minusSeconds(36_000) + "&to=" + NOW + "&pageSize=500";
        List<Instant> times = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(get(base + (cursor == null ? "" : "&cursor=" + cursor)));
            pageSizes.add(page.get("items").size());
            page.get("items").forEach(item -> times.add(Instant.parse(item.get("measuredAt").asString())));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(500, 500, 200);
        assertThat(times).hasSize(1200).doesNotHaveDuplicates().isSorted();
        assertThat(times.getFirst()).isEqualTo(NOW.minusSeconds(36_000));
        assertThat(times.getLast()).isEqualTo(NOW.minusSeconds(30));
    }

    @Test
    void rangeLongerThan31DaysIsRejected() {
        MvcTestResult result = get("/api/v1/devices/" + RANGE + "/measurements?from=" + NOW.minus(40, ChronoUnit.DAYS) + "&to=" + NOW);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("31 days");
    }

    @Test
    void fromMustBeBeforeTo() {
        assertThat(get("/api/v1/devices/" + RANGE + "/measurements?from=" + NOW + "&to=" + NOW)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // --- Series ---------------------------------------------------------------------------------------------

    @Test
    void shortRangeReturnsRawPoints() {
        JsonNode body = json(get("/api/v1/devices/" + MONTH + "/series?metrics=temperature&from=" + NOW.minusSeconds(3600) + "&to=" + NOW));

        assertThat(body.get("resolution").asString()).isEqualTo("raw");
        assertThat(body.has("bucketSeconds")).isFalse();
        JsonNode temperature = body.get("series").get(0);
        assertThat(temperature.get("metric").asString()).isEqualTo("temperature");
        assertThat(temperature.get("unit").asString()).isEqualTo("°C");
        assertThat(temperature.get("points").size()).isEqualTo(120);
        assertThat(temperature.get("points").get(0).has("value")).isTrue();
    }

    @Test
    void thirtyDaysAreAggregatedIntoSixHourBuckets() {
        JsonNode body = json(get("/api/v1/devices/" + MONTH + "/series?metrics=temperature&from=" + NOW.minus(30, ChronoUnit.DAYS)
                + "&to=" + NOW + "&maxPoints=500"));

        assertThat(body.get("resolution").asString()).isEqualTo("6h");
        assertThat(body.get("bucketSeconds").asLong()).isEqualTo(21_600);
        JsonNode points = body.get("series").get(0).get("points");
        assertThat(points.size()).isBetween(120, 121);
        long total = 0;
        for (JsonNode bucket : points) {
            assertThat(bucket.get("min").asDouble()).isLessThanOrEqualTo(bucket.get("avg").asDouble());
            assertThat(bucket.get("avg").asDouble()).isLessThanOrEqualTo(bucket.get("max").asDouble());
            total += bucket.get("count").asLong();
        }
        assertThat(total).isEqualTo(30 * 2880);
    }

    @Test
    void metricNeverReportedHasNoPoints() {
        JsonNode body = json(get("/api/v1/devices/" + MONTH + "/series?metrics=temperature,co2&from=" + NOW.minusSeconds(3600)));

        assertThat(body.get("series").get(1).get("metric").asString()).isEqualTo("co2");
        assertThat(body.get("series").get(1).get("unit").asString()).isEqualTo("ppm");
        assertThat(body.get("series").get(1).get("points").size()).isZero();
    }

    @Test
    void seriesParametersAreValidated() {
        assertThat(get("/api/v1/devices/" + MONTH + "/series?metrics=a,b,c,d,e&from=" + NOW.minusSeconds(3600))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/devices/" + MONTH + "/series?metrics=temperature&maxPoints=2001&from=" + NOW.minusSeconds(3600)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/devices/" + MONTH + "/series?metrics=Temp%3BDROP&from=" + NOW.minusSeconds(3600)))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    // --- Metrics --------------------------------------------------------------------------------------------

    @Test
    void ruuviDf5MetricsAreListedWithUnits() {
        JsonNode body = json(get("/api/v1/devices/" + RUUVI + "/metrics"));

        Set<String> metrics = new HashSet<>();
        body.forEach(m -> metrics.add(m.get("metric").asString() + "=" + m.get("unit").asString()));
        assertThat(metrics).containsExactlyInAnyOrder("temperature=°C", "humidity=%", "pressure=hPa", "acceleration_x=g",
                "acceleration_y=g", "acceleration_z=g", "battery_voltage=V", "tx_power=dBm", "movement_counter=",
                "measurement_sequence=");
        assertThat(body.get(0).get("description").asString()).isNotBlank();
    }

    // --- Access ---------------------------------------------------------------------------------------------

    @Test
    void historyRequiresAToken() {
        assertThat(mvc.get().uri("/api/v1/devices/" + LATEST + "/measurements/latest").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/devices/" + LATEST + "/metrics")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + AccessTokens.withRoles()).exchange()).hasStatus(HttpStatus.FORBIDDEN);
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
