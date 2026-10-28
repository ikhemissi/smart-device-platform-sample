package io.github.ikhemissi.sdp.alerts.evaluation;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Thresholds, their evaluation state and alerts, as seen by the database role {@code sdp_alerting}. */
@Repository
public class AlertStore {

    /** A threshold with its evaluation state, locked for the duration of the transaction. */
    public record LockedThreshold(UUID id, String deviceId, String metric, BigDecimal min, BigDecimal max,
                                  List<String> recipients, boolean enabled, Instant lastEvaluatedAt, String lastMessageKey) {
    }

    /** The open alert of a threshold. */
    public record OpenAlert(long id, String direction, BigDecimal worstValue) {
    }

    private final JdbcClient jdbc;

    public AlertStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Enabled thresholds of a device: id and metric (cached by {@link ThresholdCache}). */
    List<ThresholdCache.Watched> enabledThresholds(String deviceId) {
        return jdbc.sql("SELECT id, metric FROM sdp.alert_thresholds WHERE device_id = :device AND enabled")
                .param("device", deviceId)
                .query((rs, i) -> new ThresholdCache.Watched(rs.getObject("id", UUID.class), rs.getString("metric")))
                .list();
    }

    /**
     * Locks the threshold's evaluation state row: concurrent evaluations of the same threshold (several replicas, or
     * threshold changes through the device API) are serialised. Empty when the threshold no longer exists.
     */
    Optional<LockedThreshold> lock(UUID thresholdId) {
        return jdbc.sql("""
                        SELECT t.id, t.device_id, t.metric, t.min_value, t.max_value, t.recipients, t.enabled,
                               s.last_evaluated_at, s.last_message_key
                          FROM sdp.alert_threshold_state s JOIN sdp.alert_thresholds t ON t.id = s.threshold_id
                         WHERE s.threshold_id = :id
                           FOR UPDATE OF s""")
                .param("id", thresholdId)
                .query((rs, i) -> new LockedThreshold(rs.getObject("id", UUID.class), rs.getString("device_id"),
                        rs.getString("metric"), rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value"),
                        strings(rs.getArray("recipients")), rs.getBoolean("enabled"), instant(rs, "last_evaluated_at"),
                        rs.getString("last_message_key")))
                .optional();
    }

    void markEvaluated(UUID thresholdId, Instant measuredAt, String messageKey) {
        jdbc.sql("UPDATE sdp.alert_threshold_state SET last_evaluated_at = :at, last_message_key = :key WHERE threshold_id = :id")
                .param("id", thresholdId).param("at", utc(measuredAt)).param("key", messageKey).update();
    }

    Optional<OpenAlert> openAlert(UUID thresholdId) {
        return jdbc.sql("SELECT id, direction, worst_value FROM sdp.alert_events WHERE threshold_id = :id AND status = 'OPEN'")
                .param("id", thresholdId)
                .query((rs, i) -> new OpenAlert(rs.getLong("id"), rs.getString("direction"), rs.getBigDecimal("worst_value")))
                .optional();
    }

    long openAlert(LockedThreshold threshold, String siteId, String direction, BigDecimal value, Instant measuredAt) {
        return jdbc.sql("""
                        INSERT INTO sdp.alert_events (threshold_id, device_id, site_id, metric, threshold_min, threshold_max,
                                                      recipients, status, direction, opened_at, first_value, worst_value,
                                                      last_value, last_breach_at)
                        VALUES (:threshold, :device, :site, :metric, :min, :max, CAST(:recipients AS text[]), 'OPEN', :direction,
                                :at, :value, :value, :value, :at)
                        RETURNING id""")
                .param("threshold", threshold.id()).param("device", threshold.deviceId()).param("site", siteId)
                .param("metric", threshold.metric()).param("min", threshold.min()).param("max", threshold.max())
                .param("recipients", threshold.recipients().toArray(String[]::new)).param("direction", direction)
                .param("at", utc(measuredAt)).param("value", value)
                .query(Long.class).single();
    }

    void recordBreach(long alertId, BigDecimal value, BigDecimal worst, Instant measuredAt) {
        jdbc.sql("UPDATE sdp.alert_events SET last_value = :value, worst_value = :worst, last_breach_at = :at WHERE id = :id")
                .param("id", alertId).param("value", value).param("worst", worst).param("at", utc(measuredAt)).update();
    }

    void close(long alertId, BigDecimal value, Instant measuredAt) {
        jdbc.sql("UPDATE sdp.alert_events SET status = 'CLOSED', closed_at = :at, last_value = :value WHERE id = :id")
                .param("id", alertId).param("value", value).param("at", utc(measuredAt)).update();
    }

    /** Outbox: the email is sent after the transaction commits, by the notification sender. */
    void queueNotification(long alertId, String kind, List<String> recipients, BigDecimal value, Instant now) {
        jdbc.sql("""
                        INSERT INTO sdp.alert_notifications (alert_id, kind, status, recipients, value, created_at, next_attempt_at)
                        VALUES (:alert, :kind, 'PENDING', CAST(:recipients AS text[]), :value, :now, :now)""")
                .param("alert", alertId).param("kind", kind).param("now", utc(now))
                .param("recipients", recipients.toArray(String[]::new)).param("value", value)
                .update();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
