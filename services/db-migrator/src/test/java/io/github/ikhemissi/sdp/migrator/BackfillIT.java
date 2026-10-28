package io.github.ikhemissi.sdp.migrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ikhemissi.sdp.codecs.DecodeResult;
import io.github.ikhemissi.sdp.codecs.MetricsJson;
import io.github.ikhemissi.sdp.codecs.PayloadCodecs;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** M3 task 1.3 — demo history backfill: realistic, decodable like live data, partitioned, idempotent. */
@SpringBootTest(properties = {
        "sdp.migrator.tasks=migrate",
        "sdp.migrator.backfill.days=2",
        "sdp.migrator.backfill.sites=backfill-01"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackfillIT extends PostgresTestBase {

    private static final Instant FIRST_LIVE = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
    private static final List<String> DEVICES = List.of("backfill-01-ruuvi5-001", "backfill-01-bthome-001", "backfill-01-cayenne-001");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BackfillTask backfill;

    private BackfillTask.Result first;

    @BeforeAll
    void seedAndBackfill() {
        jdbc.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('backfill-01', 'Backfill'), ('other-01', 'Other') ON CONFLICT DO NOTHING");
        String[] formats = {"ruuvi-df5", "bthome-v2", "cayenne-lpp"};
        for (int i = 0; i < DEVICES.size(); i++) {
            jdbc.update("""
                    INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                    VALUES (?, 'backfill-01', ?, ?, now())""", DEVICES.get(i), formats[i], Timestamp.from(FIRST_LIVE));
        }
        jdbc.update("""
                INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                VALUES ('other-01-ruuvi5-001', 'other-01', 'ruuvi-df5', ?, now())""", Timestamp.from(FIRST_LIVE));
        first = backfill.run();
    }

    @Test
    void generatesTwoDaysAtTheReportingIntervalUpToTheFirstLiveMeasurement() {
        assertThat(first.devices()).isEqualTo(3);
        long expectedPerDevice = Duration.ofDays(2).minus(Duration.ofHours(1)).toSeconds() / 30;
        for (String device : DEVICES) {
            Map<String, Object> range = jdbc.queryForMap("""
                    SELECT count(*) AS n, min(measured_at) AS first, max(measured_at) AS last
                      FROM sdp.measurements WHERE device_id = ? AND message_key LIKE 'backfill-%'""", device);
            assertThat((Long) range.get("n")).as(device).isCloseTo(expectedPerDevice, within(expectedPerDevice / 50));
            assertThat(((Timestamp) range.get("last")).toInstant()).isBefore(FIRST_LIVE);
            assertThat(((Timestamp) range.get("first")).toInstant())
                    .isBetween(Instant.now().minus(Duration.ofDays(2)).minusSeconds(60), Instant.now().minus(Duration.ofDays(2)).plusSeconds(60));
        }
        assertThat(first.skippedReadings()).isZero();
    }

    @Test
    void storedRowsAreExactlyWhatTheDecoderWouldStore() {
        List<Map<String, Object>> sample = jdbc.queryForList("""
                SELECT payload_format, raw_payload, metrics::text AS metrics, attributes::text AS attributes
                  FROM sdp.measurements WHERE device_id = ANY (?) AND message_key LIKE 'backfill-%'
                 ORDER BY random() LIMIT 30""", (Object) DEVICES.toArray(String[]::new));
        assertThat(sample).hasSize(30);
        for (Map<String, Object> row : sample) {
            DecodeResult.Decoded decoded = (DecodeResult.Decoded) PayloadCodecs.decode((byte[]) row.get("raw_payload"));
            assertThat(row.get("payload_format")).isEqualTo(decoded.format().id());
            assertThat(json(row.get("metrics"))).isEqualTo(json(MetricsJson.numbers(decoded.metrics())));
            assertThat(json(row.get("attributes"))).isEqualTo(json(MetricsJson.strings(decoded.attributes())));
        }
    }

    @Test
    void readingsArePlausibleAndFollowADailyCycle() {
        Map<String, Object> temperature = jdbc.queryForMap("""
                SELECT min((metrics->>'temperature')::float8) AS lo, max((metrics->>'temperature')::float8) AS hi
                  FROM sdp.measurements WHERE device_id = 'backfill-01-ruuvi5-001'""");
        assertThat((Double) temperature.get("lo")).isBetween(10.0, 26.0);
        assertThat((Double) temperature.get("hi") - (Double) temperature.get("lo")).as("daily amplitude").isGreaterThan(2.0);
    }

    @Test
    void historyLandsInDailyPartitionsNotTheDefaultOne() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sdp.measurements_default WHERE device_id = ANY (?)", Long.class, (Object) DEVICES.toArray(String[]::new)))
                .isZero();
    }

    @Test
    void devicesAndReportedMetricsReflectTheHistory() {
        Instant firstSeen = jdbc.queryForObject("SELECT first_seen_at FROM sdp.devices WHERE device_id = 'backfill-01-ruuvi5-001'",
                Timestamp.class).toInstant();
        Instant oldest = jdbc.queryForObject("SELECT min(measured_at) FROM sdp.measurements WHERE device_id = 'backfill-01-ruuvi5-001'",
                Timestamp.class).toInstant();
        assertThat(firstSeen).isEqualTo(oldest);
        assertThat(jdbc.queryForList("SELECT metric FROM sdp.device_metrics WHERE device_id = 'backfill-01-cayenne-001' ORDER BY metric", String.class))
                .containsExactly("humidity", "illuminance", "pressure", "temperature");
    }

    @Test
    void onlySelectedSitesAreBackfilledAndASecondRunAddsNothing() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sdp.measurements WHERE device_id = 'other-01-ruuvi5-001'", Long.class)).isZero();
        long before = jdbc.queryForObject("SELECT count(*) FROM sdp.measurements WHERE device_id = ANY (?)", Long.class,
                (Object) DEVICES.toArray(String[]::new));

        BackfillTask.Result again = backfill.run();

        assertThat(again.measurements()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sdp.measurements WHERE device_id = ANY (?)", Long.class,
                (Object) DEVICES.toArray(String[]::new))).isEqualTo(before);
    }

    private Object json(Object text) {
        return jdbc.queryForObject("SELECT ?::jsonb", Object.class, text.toString()).toString();
    }
}
