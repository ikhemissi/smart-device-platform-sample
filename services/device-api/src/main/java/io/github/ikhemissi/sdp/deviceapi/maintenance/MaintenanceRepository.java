package io.github.ikhemissi.sdp.deviceapi.maintenance;

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

/** Maintenance of the database role {@code sdp_catalog}: read, and record Editors' statuses only (migration V10). */
@Repository
public class MaintenanceRepository {

    private static final String SELECT = """
            SELECT i.id, i.device_id, d.display_name AS device_name, i.site_id, i.status, i.probability,
                   i.reasons::text AS reasons, i.battery::text AS battery, i.work_order, i.work_order_model, i.work_order_at,
                   i.opened_at, i.updated_at, i.resolved_at, i.status_by, i.status_at, i.status_comment
              FROM sdp.maintenance_items i
              LEFT JOIN sdp.devices d ON d.device_id = i.device_id
            """;

    private final JdbcClient jdbc;

    public MaintenanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Items of the given sites (all when null) and statuses, most likely first. */
    List<MaintenanceItem> items(String siteId, List<String> sites, List<String> statuses, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE i.status = ANY(CAST(:statuses AS text[]))");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("statuses", statuses.toArray(String[]::new));
        if (siteId != null) {
            sql.append(" AND i.site_id = :site");
            params.put("site", siteId);
        }
        if (!sites.isEmpty()) {
            sql.append(" AND i.site_id = ANY(CAST(:sites AS text[]))");
            params.put("sites", sites.toArray(String[]::new));
        }
        sql.append(" ORDER BY i.probability DESC, i.opened_at DESC, i.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(MaintenanceRepository::item).list();
    }

    Optional<MaintenanceItem> item(long id) {
        return jdbc.sql(SELECT + " WHERE i.id = :id").param("id", id).query(MaintenanceRepository::item).optional();
    }

    Optional<MaintenanceItem> queued(String deviceId) {
        return jdbc.sql(SELECT + " WHERE i.device_id = :device AND i.status IN ('open', 'planned')")
                .param("device", deviceId).query(MaintenanceRepository::item).optional();
    }

    Optional<DeviceMaintenance.Assessment> assessment(String deviceId) {
        return jdbc.sql("""
                        SELECT assessed_at, battery::text AS battery, signals::text AS signals, probability, decision_model,
                               decision_model_version
                          FROM sdp.device_maintenance WHERE device_id = :device""")
                .param("device", deviceId)
                .query((rs, row) -> new DeviceMaintenance.Assessment(instant(rs, "assessed_at"), rs.getString("battery"),
                        rs.getString("signals"), rs.getDouble("probability"), rs.getString("decision_model"),
                        rs.getString("decision_model_version")))
                .optional();
    }

    /** Sets an open or planned item's status; false when the item was closed meanwhile. */
    boolean setStatus(long id, String status, String comment, String by, Instant now) {
        return jdbc.sql("""
                        UPDATE sdp.maintenance_items SET status = :status, status_by = :by, status_at = :now,
                               status_comment = :comment, updated_at = :now
                         WHERE id = :id AND status IN ('open', 'planned')""")
                .param("id", id).param("status", status).param("by", by).param("now", utc(now)).param("comment", comment)
                .update() == 1;
    }

    Optional<String> deviceSite(String deviceId) {
        return jdbc.sql("SELECT site_id FROM sdp.devices WHERE device_id = :device")
                .param("device", deviceId).query(String.class).optional();
    }

    private static MaintenanceItem item(ResultSet rs, int row) throws SQLException {
        return new MaintenanceItem(rs.getLong("id"), rs.getString("device_id"), rs.getString("device_name"),
                rs.getString("site_id"), rs.getString("status"), rs.getDouble("probability"), rs.getString("reasons"),
                rs.getString("battery"), rs.getString("work_order"), rs.getString("work_order_model"),
                instant(rs, "work_order_at"), instant(rs, "opened_at"), instant(rs, "updated_at"), instant(rs, "resolved_at"),
                rs.getString("status_by"), instant(rs, "status_at"), rs.getString("status_comment"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
