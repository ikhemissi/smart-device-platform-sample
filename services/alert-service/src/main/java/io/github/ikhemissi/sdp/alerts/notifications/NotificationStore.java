package io.github.ikhemissi.sdp.alerts.notifications;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The notification outbox ({@code alert_notifications}). */
@Repository
public class NotificationStore {

    /** Single reminder scheduler across replicas (transaction-scoped advisory lock). */
    private static final long REMINDER_LOCK = 0x5D9A1E47L;

    private final JdbcClient jdbc;

    public NotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The oldest due notification, locked until the end of the transaction; notifications locked by another replica
     * are skipped, so each email is sent by one replica only.
     */
    Optional<PendingNotification> claimNext(Instant now) {
        return jdbc.sql("""
                        SELECT n.id, n.kind, n.recipients, n.value, n.attempts, n.created_at,
                               a.id AS alert_id, a.device_id, d.display_name AS device_name, a.site_id, s.display_name AS site_name,
                               a.metric, md.description AS metric_description, md.unit, a.threshold_min, a.threshold_max,
                               a.direction, a.opened_at, a.closed_at, a.worst_value, a.last_breach_at
                          FROM sdp.alert_notifications n
                          JOIN sdp.alert_events a ON a.id = n.alert_id
                          LEFT JOIN sdp.devices d ON d.device_id = a.device_id
                          LEFT JOIN sdp.sites s ON s.site_id = a.site_id
                          LEFT JOIN sdp.metric_definitions md ON md.name = regexp_replace(a.metric, '_ch[0-9]+$', '')
                         WHERE n.status = 'PENDING' AND n.next_attempt_at <= :now
                         ORDER BY n.next_attempt_at, n.id
                         LIMIT 1
                           FOR UPDATE OF n SKIP LOCKED""")
                .param("now", utc(now))
                .query(NotificationStore::pending)
                .optional();
    }

    void markSent(PendingNotification notification, Instant now) {
        jdbc.sql("UPDATE sdp.alert_notifications SET status = 'SENT', sent_at = :now, attempts = attempts + 1, last_error = NULL WHERE id = :id")
                .param("id", notification.id()).param("now", utc(now)).update();
        if (!PendingNotification.RESOLVED.equals(notification.kind())) {
            jdbc.sql("UPDATE sdp.alert_events SET last_notified_at = :now WHERE id = :id")
                    .param("id", notification.alertId()).param("now", utc(now)).update();
        }
    }

    void markAttemptFailed(long id, String error, Instant nextAttempt) {
        jdbc.sql("UPDATE sdp.alert_notifications SET attempts = attempts + 1, last_error = :error, next_attempt_at = :next WHERE id = :id")
                .param("id", id).param("error", truncate(error)).param("next", utc(nextAttempt)).update();
    }

    void markFailed(long id, String error) {
        jdbc.sql("UPDATE sdp.alert_notifications SET status = 'FAILED', attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("id", id).param("error", truncate(error)).update();
    }

    /**
     * Queues a reminder for each open alert whose breach is still ongoing (last breaching measurement at or after
     * {@code ongoingSince}) and whose last email was sent at or before {@code dueBefore}. Runs on one replica at a time.
     *
     * @return the number of reminders queued, or -1 when another replica holds the scheduler lock
     */
    int queueReminders(Instant ongoingSince, Instant dueBefore, Instant now) {
        Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:lock)").param("lock", REMINDER_LOCK).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(locked)) {
            return -1;
        }
        return jdbc.sql("""
                        INSERT INTO sdp.alert_notifications (alert_id, kind, status, recipients, value, created_at, next_attempt_at)
                        SELECT a.id, 'REMINDER', 'PENDING', a.recipients, a.last_value, :now, :now
                          FROM sdp.alert_events a
                         WHERE a.status = 'OPEN' AND a.last_breach_at >= :ongoing
                           AND a.last_notified_at IS NOT NULL AND a.last_notified_at <= :due
                           AND NOT EXISTS (SELECT 1 FROM sdp.alert_notifications n WHERE n.alert_id = a.id AND n.status = 'PENDING')""")
                .param("ongoing", utc(ongoingSince)).param("due", utc(dueBefore)).param("now", utc(now))
                .update();
    }

    private static PendingNotification pending(ResultSet rs, int row) throws SQLException {
        return new PendingNotification(rs.getLong("id"), rs.getString("kind"), strings(rs.getArray("recipients")),
                rs.getBigDecimal("value"), rs.getInt("attempts"), instant(rs, "created_at"), rs.getLong("alert_id"),
                rs.getString("device_id"), rs.getString("device_name"), rs.getString("site_id"), rs.getString("site_name"),
                rs.getString("metric"), rs.getString("metric_description"), rs.getString("unit"),
                rs.getBigDecimal("threshold_min"), rs.getBigDecimal("threshold_max"), rs.getString("direction"),
                instant(rs, "opened_at"), instant(rs, "closed_at"), rs.getBigDecimal("worst_value"), instant(rs, "last_breach_at"));
    }

    private static String truncate(String error) {
        return error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error;
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
