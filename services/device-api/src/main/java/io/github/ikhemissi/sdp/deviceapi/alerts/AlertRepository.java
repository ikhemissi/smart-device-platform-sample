package io.github.ikhemissi.sdp.deviceapi.alerts;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Thresholds (read/write) and alerts (read; cancellation only) of the database role {@code sdp_catalog}. */
@Repository
public class AlertRepository {

    private static final String THRESHOLD_COLUMNS = """
            t.id, t.device_id, t.metric, md.unit, t.min_value, t.max_value, t.recipients, t.enabled, t.version,
            t.updated_at, t.updated_by_name,
            (SELECT a.id FROM sdp.alert_events a WHERE a.threshold_id = t.id AND a.status = 'OPEN') AS open_alert_id""";
    private static final String UNIT_JOIN = "LEFT JOIN sdp.metric_definitions md ON md.name = regexp_replace(%s, '_ch[0-9]+$', '')";

    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- Thresholds -----------------------------------------------------------------------------------------------

    List<Threshold> thresholds(String deviceId) {
        return jdbc.sql("SELECT " + THRESHOLD_COLUMNS + " FROM sdp.alert_thresholds t " + UNIT_JOIN.formatted("t.metric")
                        + " WHERE t.device_id = :device ORDER BY t.metric, t.created_at")
                .param("device", deviceId).query(AlertRepository::threshold).list();
    }

    Optional<Threshold> threshold(String deviceId, UUID id) {
        return jdbc.sql("SELECT " + THRESHOLD_COLUMNS + " FROM sdp.alert_thresholds t " + UNIT_JOIN.formatted("t.metric")
                        + " WHERE t.device_id = :device AND t.id = :id")
                .param("device", deviceId).param("id", id).query(AlertRepository::threshold).optional();
    }

    /** Locks the threshold's evaluation state: serialises the change with the alert service's evaluation. */
    Optional<Integer> lockThreshold(String deviceId, UUID id) {
        return jdbc.sql("""
                        SELECT t.version FROM sdp.alert_thresholds t JOIN sdp.alert_threshold_state s ON s.threshold_id = t.id
                         WHERE t.device_id = :device AND t.id = :id FOR UPDATE""")
                .param("device", deviceId).param("id", id).query(Integer.class).optional();
    }

    int countThresholds(String deviceId) {
        return jdbc.sql("SELECT count(*) FROM sdp.alert_thresholds WHERE device_id = :device")
                .param("device", deviceId).query(Integer.class).single();
    }

    boolean deviceExists(String deviceId) {
        return jdbc.sql("SELECT count(*) FROM sdp.devices WHERE device_id = :device")
                .param("device", deviceId).query(Integer.class).single() > 0;
    }

    /** The device has reported this metric (thresholds can only watch metrics the device sends). */
    boolean reportsMetric(String deviceId, String metric) {
        return jdbc.sql("SELECT count(*) FROM sdp.device_metrics WHERE device_id = :device AND metric = :metric")
                .param("device", deviceId).param("metric", metric).query(Integer.class).single() > 0;
    }

    void insertThreshold(UUID id, String deviceId, ThresholdInput input, Instant now, String userId, String userName) {
        jdbc.sql("""
                        INSERT INTO sdp.alert_thresholds (id, device_id, metric, min_value, max_value, recipients, enabled,
                                                          created_at, created_by, updated_at, updated_by, updated_by_name)
                        VALUES (:id, :device, :metric, :min, :max, CAST(:recipients AS text[]), :enabled, :now, :user, :now, :user, :name)""")
                .param("id", id).param("device", deviceId).param("metric", input.metric().strip())
                .param("min", input.min()).param("max", input.max())
                .param("recipients", input.normalizedRecipients().toArray(String[]::new))
                .param("enabled", input.enabledOrDefault()).param("now", utc(now))
                .param("user", userId).param("name", userName)
                .update();
        jdbc.sql("INSERT INTO sdp.alert_threshold_state (threshold_id) VALUES (:id)").param("id", id).update();
    }

    void updateThreshold(UUID id, ThresholdInput input, Instant now, String userId, String userName) {
        jdbc.sql("""
                        UPDATE sdp.alert_thresholds
                           SET metric = :metric, min_value = :min, max_value = :max, recipients = CAST(:recipients AS text[]),
                               enabled = :enabled, version = version + 1, updated_at = :now, updated_by = :user,
                               updated_by_name = :name
                         WHERE id = :id""")
                .param("id", id).param("metric", input.metric().strip())
                .param("min", input.min()).param("max", input.max())
                .param("recipients", input.normalizedRecipients().toArray(String[]::new))
                .param("enabled", input.enabledOrDefault()).param("now", utc(now))
                .param("user", userId).param("name", userName)
                .update();
    }

    void deleteThreshold(UUID id) {
        jdbc.sql("DELETE FROM sdp.alert_thresholds WHERE id = :id").param("id", id).update();
    }

    /** Closes the threshold's open alert without notification (threshold disabled, changed metric, or deleted). */
    int cancelOpenAlert(UUID thresholdId, Instant now) {
        return jdbc.sql("UPDATE sdp.alert_events SET status = 'CANCELLED', closed_at = :now WHERE threshold_id = :id AND status = 'OPEN'")
                .param("id", thresholdId).param("now", utc(now)).update();
    }

    // --- Alerts ---------------------------------------------------------------------------------------------------

    /** Alerts overlapping [from, to), most recent first. */
    List<Alert> alerts(String deviceId, String siteId, String status, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.*, d.display_name AS device_name, md.unit
                  FROM sdp.alert_events a
                  LEFT JOIN sdp.devices d ON d.device_id = a.device_id
                  """).append(UNIT_JOIN.formatted("a.metric")).append("""

                 WHERE a.opened_at < :to AND (a.closed_at IS NULL OR a.closed_at >= :from)""");
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
        if (status != null) {
            sql.append(" AND a.status = :status");
            params.put("status", status);
        }
        sql.append(" ORDER BY a.opened_at DESC, a.id DESC LIMIT :limit");
        params.put("limit", limit);
        List<Alert> alerts = jdbc.sql(sql.toString()).params(params).query(AlertRepository::alert).list();
        return withNotifications(alerts);
    }

    private List<Alert> withNotifications(List<Alert> alerts) {
        if (alerts.isEmpty()) {
            return alerts;
        }
        Map<Long, List<Alert.Notification>> byAlert = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT alert_id, kind, status, cardinality(recipients) AS recipients, attempts, created_at, sent_at, last_error
                          FROM sdp.alert_notifications WHERE alert_id = ANY(CAST(:ids AS bigint[])) ORDER BY created_at, id""")
                .param("ids", alerts.stream().map(Alert::id).toArray(Long[]::new))
                .query(rs -> {
                    byAlert.computeIfAbsent(rs.getLong("alert_id"), id -> new ArrayList<>()).add(new Alert.Notification(
                            rs.getString("kind"), rs.getString("status"), rs.getInt("recipients"), rs.getInt("attempts"),
                            instant(rs, "created_at"), instant(rs, "sent_at"), rs.getString("last_error")));
                });
        return alerts.stream().map(a -> new Alert(a.id(), a.thresholdId(), a.deviceId(), a.deviceName(), a.siteId(), a.metric(),
                a.unit(), a.thresholdMin(), a.thresholdMax(), a.direction(), a.status(), a.openedAt(), a.closedAt(), a.firstValue(),
                a.worstValue(), a.lastValue(), a.lastBreachAt(), byAlert.getOrDefault(a.id(), List.of()))).toList();
    }

    // --- Mapping --------------------------------------------------------------------------------------------------

    private static Threshold threshold(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Threshold(id, rs.getString("device_id"), rs.getString("metric"), rs.getString("unit"),
                rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value"), strings(rs.getArray("recipients")),
                rs.getBoolean("enabled"), Threshold.entityTag(id, rs.getInt("version")), instant(rs, "updated_at"),
                rs.getString("updated_by_name"), rs.getObject("open_alert_id", Long.class));
    }

    private static Alert alert(ResultSet rs, int row) throws SQLException {
        return new Alert(rs.getLong("id"), rs.getObject("threshold_id", UUID.class), rs.getString("device_id"),
                rs.getString("device_name"), rs.getString("site_id"), rs.getString("metric"), rs.getString("unit"),
                rs.getBigDecimal("threshold_min"), rs.getBigDecimal("threshold_max"), rs.getString("direction"),
                rs.getString("status"), instant(rs, "opened_at"), instant(rs, "closed_at"), number(rs, "first_value"),
                number(rs, "worst_value"), number(rs, "last_value"), instant(rs, "last_breach_at"), List.of());
    }

    private static BigDecimal number(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
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
