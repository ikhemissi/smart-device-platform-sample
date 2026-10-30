package io.github.ikhemissi.sdp.deviceapi.anomalies;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Anomalies of the database role {@code sdp_catalog}: read, and record reviews only (migration V8). */
@Repository
public class AnomalyRepository {

    static final List<String> CLASSES = List.of("real_event", "sensor_fault", "expected_pattern", "cannot_tell");
    private static final String SELECT = """
            SELECT a.*, d.display_name AS device_name, md.unit,
                   (a.probabilities ->> a.classification)::float8 AS confidence,
                   (a.probabilities ->> 'real_event')::float8 AS p_real_event,
                   (a.probabilities ->> 'sensor_fault')::float8 AS p_sensor_fault,
                   (a.probabilities ->> 'expected_pattern')::float8 AS p_expected_pattern,
                   (a.probabilities ->> 'cannot_tell')::float8 AS p_cannot_tell
              FROM sdp.anomalies a
              LEFT JOIN sdp.devices d ON d.device_id = a.device_id
              LEFT JOIN sdp.metric_definitions md ON md.name = a.metric
            """;
    /** The status shown to users: a review supersedes the automated decision. */
    private static final String STATUS = "CASE WHEN a.reviewed_at IS NOT NULL THEN 'reviewed' ELSE a.decision END";

    private final JdbcClient jdbc;

    public AnomalyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Anomalies overlapping [from, to), most recent first. */
    List<Anomaly> anomalies(String deviceId, String siteId, List<String> statuses, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE a.started_at < :to AND a.ended_at >= :from");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", utc(from));
        params.put("to", utc(to));
        if (deviceId != null) {
            sql.append(" AND a.device_id = :device");
            params.put("device", deviceId);
        }
        if (siteId != null) {
            sql.append(" AND a.site_id = :site");
            params.put("site", siteId);
        }
        sql.append(" AND ").append(STATUS).append(" = ANY(CAST(:statuses AS text[]))");
        params.put("statuses", statuses.toArray(String[]::new));
        sql.append(" ORDER BY a.started_at DESC, a.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(AnomalyRepository::anomaly).list();
    }

    Optional<Anomaly> anomaly(long id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", id).query(AnomalyRepository::anomaly).optional();
    }

    void review(long id, String verdict, String comment, String reviewer, Instant now) {
        jdbc.sql("""
                        UPDATE sdp.anomalies SET reviewed_at = :now, reviewed_by = :reviewer, review_verdict = :verdict,
                               review_comment = :comment
                         WHERE id = :id""")
                .param("id", id).param("now", utc(now)).param("reviewer", reviewer).param("verdict", verdict)
                .param("comment", comment).update();
    }

    boolean deviceExists(String deviceId) {
        return jdbc.sql("SELECT count(*) FROM sdp.devices WHERE device_id = :device")
                .param("device", deviceId).query(Integer.class).single() > 0;
    }

    private static Anomaly anomaly(ResultSet rs, int row) throws SQLException {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (String name : CLASSES) {
            Double p = rs.getObject("p_" + name, Double.class);
            if (p != null) {
                probabilities.put(name, p);
            }
        }
        Instant reviewedAt = instant(rs, "reviewed_at");
        Anomaly.Review review = reviewedAt == null ? null : new Anomaly.Review(reviewedAt, rs.getString("reviewed_by"),
                rs.getString("review_verdict"), rs.getString("review_comment"));
        String decision = rs.getString("decision");
        return new Anomaly(rs.getLong("id"), rs.getString("device_id"), rs.getString("device_name"), rs.getString("site_id"),
                rs.getString("metric"), rs.getString("unit"), rs.getString("kind"), instant(rs, "started_at"),
                instant(rs, "ended_at"), rs.getDouble("observed_min"), rs.getDouble("observed_max"),
                rs.getDouble("observed_mean"), rs.getDouble("observed_last"), rs.getDouble("usual_value"),
                rs.getDouble("spread"), rs.getDouble("score"), review != null ? "reviewed" : decision, decision,
                rs.getString("classification"), rs.getObject("confidence", Double.class), probabilities,
                rs.getObject("confidence_threshold", Double.class), rs.getString("decision_model"),
                rs.getString("decision_model_version"), rs.getString("explanation"), rs.getString("explanation_model"),
                instant(rs, "detected_at"), instant(rs, "updated_at"), review);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
