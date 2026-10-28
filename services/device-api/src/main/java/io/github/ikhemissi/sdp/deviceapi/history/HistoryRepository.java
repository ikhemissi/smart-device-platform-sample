package io.github.ikhemissi.sdp.deviceapi.history;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Telemetry history queries: always by device and time range, so only the relevant daily partitions are read. */
@Repository
public class HistoryRepository {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public HistoryRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean deviceExists(String deviceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sdp.devices WHERE device_id = :id)").param("id", deviceId)
                .query(Boolean.class).single();
    }

    List<Measurement> latest(String deviceId, int count) {
        return jdbc.sql("""
                        SELECT measured_at, payload_format, metrics::text AS metrics, attributes::text AS attributes
                          FROM sdp.measurements WHERE device_id = :id
                         ORDER BY measured_at DESC, message_key DESC LIMIT :count""")
                .param("id", deviceId).param("count", count)
                .query(this::measurement).list();
    }

    /** Ascending page after the keyset position {@code (afterTime, afterKey)}, when given. */
    List<KeyedMeasurement> range(String deviceId, Instant from, Instant to, Cursor after, int limit) {
        return jdbc.sql("""
                        SELECT measured_at, message_key, payload_format, metrics::text AS metrics, attributes::text AS attributes
                          FROM sdp.measurements
                         WHERE device_id = :id AND measured_at >= :from AND measured_at < :to
                           AND (CAST(:afterTime AS timestamptz) IS NULL OR (measured_at, message_key) > (:afterTime, :afterKey))
                         ORDER BY measured_at, message_key LIMIT :limit""")
                .param("id", deviceId).param("from", utc(from)).param("to", utc(to))
                .param("afterTime", after == null ? null : utc(after.measuredAt()))
                .param("afterKey", after == null ? "" : after.messageKey())
                .param("limit", limit)
                .query((rs, i) -> new KeyedMeasurement(measurement(rs, i), rs.getString("message_key"))).list();
    }

    record KeyedMeasurement(Measurement measurement, String messageKey) {
    }

    /** Number of measurements in the range, counting at most {@code limit}. */
    long countUpTo(String deviceId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT count(*) FROM (SELECT 1 FROM sdp.measurements
                                               WHERE device_id = :id AND measured_at >= :from AND measured_at < :to
                                               LIMIT :limit) bounded""")
                .param("id", deviceId).param("from", utc(from)).param("to", utc(to)).param("limit", limit)
                .query(Long.class).single();
    }

    /** Raw values of one metric over {@code [from, to)}. */
    void raw(String deviceId, String metric, Instant from, Instant to, PointSink sink) {
        jdbc.sql("""
                        SELECT measured_at, (metrics->>:metric)::float8 AS v FROM sdp.measurements
                         WHERE device_id = :id AND measured_at >= :from AND measured_at < :to AND metrics->>:metric IS NOT NULL
                         ORDER BY measured_at""")
                .param("id", deviceId).param("metric", metric).param("from", utc(from)).param("to", utc(to))
                .query(rs -> {
                    sink.raw(instant(rs, "measured_at"), rs.getDouble("v"));
                });
    }

    /** Buckets of one metric over {@code [from, to)}, aligned on a fixed grid. */
    void buckets(String deviceId, String metric, long bucketSeconds, Instant from, Instant to, PointSink sink) {
        jdbc.sql("""
                        SELECT date_bin(make_interval(secs => :bucket), measured_at, TIMESTAMPTZ '2000-01-01 00:00:00+00') AS bucket,
                               min(v) AS lo, avg(v) AS mean, max(v) AS hi, count(v) AS n
                          FROM (SELECT measured_at, (metrics->>:metric)::float8 AS v FROM sdp.measurements
                                 WHERE device_id = :id AND measured_at >= :from AND measured_at < :to AND metrics->>:metric IS NOT NULL) values
                         GROUP BY 1 ORDER BY 1""")
                .param("id", deviceId).param("metric", metric).param("bucket", bucketSeconds)
                .param("from", utc(from)).param("to", utc(to))
                .query(rs -> {
                    sink.bucket(instant(rs, "bucket"), rs.getDouble("lo"), rs.getDouble("mean"), rs.getDouble("hi"), rs.getLong("n"));
                });
    }

    interface PointSink {
        void raw(Instant at, double value);

        void bucket(Instant at, double min, double avg, double max, long count);
    }

    List<ReportedMetric> metrics(String deviceId, Instant since) {
        return jdbc.sql("""
                        SELECT dm.metric, md.unit, md.description, dm.last_seen_at
                          FROM sdp.device_metrics dm
                          LEFT JOIN sdp.metric_definitions md ON md.name = regexp_replace(dm.metric, '_ch[0-9]+$', '')
                         WHERE dm.device_id = :id AND dm.last_seen_at >= :since
                         ORDER BY dm.metric""")
                .param("id", deviceId).param("since", utc(since))
                .query((rs, i) -> new ReportedMetric(rs.getString(1), rs.getString(2), rs.getString(3), instant(rs, "last_seen_at")))
                .list();
    }

    /** Units of the given metric keys (channel-suffixed keys use their base metric's unit). */
    Map<String, String> units(List<String> metrics) {
        Map<String, String> units = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT k, md.unit FROM unnest(CAST(:metrics AS text[])) AS k
                          LEFT JOIN sdp.metric_definitions md ON md.name = regexp_replace(k, '_ch[0-9]+$', '')""")
                .param("metrics", metrics.toArray(String[]::new))
                .query(rs -> {
                    units.put(rs.getString(1), rs.getString(2));
                });
        return units;
    }

    /** Partitions that reported a watermark, and the lowest watermark. */
    Watermarks watermarks() {
        return jdbc.sql("SELECT count(*) AS partitions, min(watermark) AS lowest FROM sdp.ingestion_watermarks")
                .query((rs, i) -> new Watermarks(rs.getInt("partitions"), instant(rs, "lowest"))).single();
    }

    record Watermarks(int partitions, Instant lowest) {
    }

    private Measurement measurement(ResultSet rs, int row) throws SQLException {
        return new Measurement(instant(rs, "measured_at"), rs.getString("payload_format"),
                json.readValue(rs.getString("metrics"), OBJECT), json.readValue(rs.getString("attributes"), STRINGS));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        // -infinity watermarks are returned as OffsetDateTime.MIN.
        return value == null ? null : value.equals(OffsetDateTime.MIN) ? Instant.MIN : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
