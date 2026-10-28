package io.github.ikhemissi.sdp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** M6 task 2.1 — hourly metric rollups maintained behind the ingestion watermark. */
@SpringBootTest(properties = "sdp.migrator.tasks=migrate")
class MetricRollupsIT extends PostgresTestBase {

    private static final Duration RETENTION = Duration.ofDays(30);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MetricRollups rollups;

    private Instant hour;

    @BeforeEach
    void setUp() {
        rollups.reset();
        jdbc.update("DELETE FROM sdp.ingestion_watermarks");
        jdbc.update("DELETE FROM sdp.measurements WHERE device_id LIKE 'rollup-%'");
        hour = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(3));
        insert("rollup-a", hour.plus(Duration.ofMinutes(5)), "{\"temperature\": 20.0, \"humidity\": 50}");
        insert("rollup-a", hour.plus(Duration.ofMinutes(40)), "{\"temperature\": 22.0, \"humidity\": 54}");
        insert("rollup-b", hour.plus(Duration.ofMinutes(10)), "{\"temperature\": 30.5, \"label\": \"x\"}");
        insert("rollup-a", Instant.now(), "{\"temperature\": 25.0}");
    }

    @Test
    void rollsUpCompleteHoursPerDeviceAndMetric() {
        watermark("now()");

        rollups.run(RETENTION);

        Map<String, Object> a = jdbc.queryForMap("""
                SELECT site_id, min_value, max_value, sum_value, sample_count, first_at, first_value, last_at, last_value
                  FROM sdp.metric_rollups_hourly WHERE metric = 'temperature' AND device_id = 'rollup-a' AND hour = ?
                """, Timestamp.from(hour));
        assertThat(a).containsEntry("site_id", "rollup-site").containsEntry("min_value", 20.0).containsEntry("max_value", 22.0)
                .containsEntry("sum_value", 42.0).containsEntry("sample_count", 2L)
                .containsEntry("first_value", 20.0).containsEntry("last_value", 22.0);
        assertThat(((Timestamp) a.get("first_at")).toInstant()).isEqualTo(hour.plus(Duration.ofMinutes(5)));
        assertThat(((Timestamp) a.get("last_at")).toInstant()).isEqualTo(hour.plus(Duration.ofMinutes(40)));
        assertThat(count("metric = 'humidity' AND device_id = 'rollup-a'")).isOne();
        assertThat(count("device_id = 'rollup-b'")).as("non-numeric values are skipped").isOne();
        assertThat(count("device_id = 'rollup-a' AND hour > ?", hour)).as("the current hour is not complete").isZero();
        assertThat(rollups.rolledUntil()).contains(
                Instant.now().minus(MetricRollups.WATERMARK_MARGIN).truncatedTo(ChronoUnit.HOURS));
    }

    @Test
    void stopsAtTheIngestionWatermark() {
        watermark("'" + hour.plus(Duration.ofMinutes(30)) + "'");
        rollups.run(RETENTION);
        assertThat(count("device_id LIKE 'rollup-%'")).isZero();
        assertThat(rollups.rolledUntil()).contains(hour);

        jdbc.update("UPDATE sdp.ingestion_watermarks SET watermark = '-infinity'");
        rollups.run(RETENTION);
        assertThat(rollups.rolledUntil()).as("a partition without progress blocks rollups").contains(hour);
    }

    @Test
    void isRepeatableAndCanBeReset() {
        watermark("now()");
        rollups.run(RETENTION);
        long rows = count("device_id LIKE 'rollup-%'");

        rollups.run(RETENTION);
        assertThat(count("device_id LIKE 'rollup-%'")).isEqualTo(rows);

        rollups.reset();
        assertThat(count("true")).isZero();
        assertThat(rollups.rolledUntil()).isEmpty();
    }

    private void insert(String deviceId, Instant measuredAt, String metrics) {
        jdbc.update("""
                INSERT INTO sdp.measurements (device_id, site_id, measured_at, received_at, message_key, payload_format, raw_payload, metrics)
                VALUES (?, 'rollup-site', ?, now(), ?, 'ruuvi-df5', '\\x05'::bytea, ?::jsonb)
                """, deviceId, Timestamp.from(measuredAt), measuredAt.toString(), metrics);
    }

    private void watermark(String expression) {
        jdbc.update("INSERT INTO sdp.ingestion_watermarks (partition_id, watermark) VALUES ('0', %s)".formatted(expression));
    }

    private long count(String where, Object... args) {
        Object[] converted = java.util.Arrays.stream(args).map(a -> a instanceof Instant i ? Timestamp.from(i) : a).toArray();
        return jdbc.queryForObject("SELECT count(*) FROM sdp.metric_rollups_hourly WHERE " + where, Long.class, converted);
    }
}
