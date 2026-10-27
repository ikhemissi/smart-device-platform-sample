package io.github.ikhemissi.sdp.deviceapi.devices;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DeviceRepository {

    private static final String COLUMNS = """
            d.device_id, d.site_id, d.display_name, d.description, d.location::text AS location, d.last_payload_format,
            d.first_seen_at, d.last_seen_at, d.updated_at, d.updated_by, d.updated_by_name""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DeviceRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<DeviceRecord> find(DeviceQuery query, Instant reportingSince) {
        Map<String, Object> params = new HashMap<>();
        String where = where(query, reportingSince, params);
        params.put("limit", query.pageSize());
        params.put("offset", query.offset());
        String direction = query.descending() ? "DESC" : "ASC";
        return jdbc.sql("SELECT " + COLUMNS + " FROM sdp.devices d" + where
                        + " ORDER BY " + query.sort().column() + " " + direction + ", d.device_id " + direction
                        + " LIMIT :limit OFFSET :offset")
                .params(params)
                .query(this::map)
                .list();
    }

    long count(DeviceQuery query, Instant reportingSince) {
        Map<String, Object> params = new HashMap<>();
        String where = where(query, reportingSince, params);
        return jdbc.sql("SELECT count(*) FROM sdp.devices d" + where).params(params).query(Long.class).single();
    }

    Optional<DeviceRecord> findById(String deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sdp.devices d WHERE d.device_id = :id")
                .param("id", deviceId).query(this::map).optional();
    }

    /** Locks the row until the end of the transaction so the entity-tag check and the update are atomic. */
    Optional<DeviceRecord> lockById(String deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sdp.devices d WHERE d.device_id = :id FOR UPDATE")
                .param("id", deviceId).query(this::map).optional();
    }

    void update(String deviceId, String displayName, String description, Location location, Instant at,
                String editorId, String editorName) {
        jdbc.sql("""
                        UPDATE sdp.devices
                           SET display_name = :displayName, description = :description, location = CAST(:location AS jsonb),
                               updated_at = :at, updated_by = :editorId, updated_by_name = :editorName
                         WHERE device_id = :id""")
                .param("displayName", displayName)
                .param("description", description)
                .param("location", location == null ? null : json.writeValueAsString(location))
                .param("at", OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
                .param("editorId", editorId)
                .param("editorName", editorName)
                .param("id", deviceId)
                .update();
    }

    private static String where(DeviceQuery query, Instant reportingSince, Map<String, Object> params) {
        var where = new StringBuilder(" WHERE true");
        if (query.siteId() != null) {
            where.append(" AND d.site_id = :siteId");
            params.put("siteId", query.siteId());
        }
        if (query.status() != null) {
            where.append(query.status() == DeviceStatus.REPORTING ? " AND d.last_seen_at >= :since" : " AND d.last_seen_at < :since");
            params.put("since", OffsetDateTime.ofInstant(reportingSince, java.time.ZoneOffset.UTC));
        }
        if (query.search() != null) {
            where.append(" AND (d.device_id ILIKE :pattern ESCAPE '\\' OR d.display_name ILIKE :pattern ESCAPE '\\')");
            params.put("pattern", "%" + query.search().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return where.toString();
    }

    private DeviceRecord map(ResultSet rs, int row) throws SQLException {
        String location = rs.getString("location");
        return new DeviceRecord(
                rs.getString("device_id"),
                rs.getString("site_id"),
                rs.getString("display_name"),
                rs.getString("description"),
                location == null ? null : json.readValue(location, Location.class),
                rs.getString("last_payload_format"),
                instant(rs, "first_seen_at"),
                instant(rs, "last_seen_at"),
                instant(rs, "updated_at"),
                rs.getString("updated_by"),
                rs.getString("updated_by_name"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
