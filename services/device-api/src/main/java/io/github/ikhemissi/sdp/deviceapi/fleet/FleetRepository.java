package io.github.ikhemissi.sdp.deviceapi.fleet;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FleetRepository {

    static final int MAX_SILENT_DEVICES = 100;

    private final JdbcClient jdbc;

    public FleetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Partial aggregate of one device: a rolled-up hour or raw measurements. */
    record Partial(String deviceId, String siteId, double min, double max, double sum, long count,
                   Instant firstAt, double firstValue, Instant lastAt, double lastValue) {
    }

    List<String> metricNames() {
        return jdbc.sql("SELECT name FROM sdp.metric_definitions ORDER BY name").query(String.class).list();
    }

    Optional<String> unit(String metric) {
        return jdbc.sql("SELECT unit FROM sdp.metric_definitions WHERE name = :metric").param("metric", metric)
                .query(String.class).optional();
    }

    boolean siteExists(String siteId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sdp.sites WHERE site_id = :id)").param("id", siteId)
                .query(Boolean.class).single();
    }

    Optional<Instant> rolledUntil() {
        return jdbc.sql("SELECT rolled_until FROM sdp.metric_rollup_state").query(OffsetDateTime.class).optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Partials of {@code metric}: rollups of the hours {@code [rolledFrom, rolledTo)} and aggregates of the raw
     * measurements in {@code [from, rawUntil)} and {@code [rawFrom, to)}.
     */
    List<Partial> partials(String metric, String siteId, Instant from, Instant rawUntil, Instant rolledFrom, Instant rolledTo,
                           Instant rawFrom, Instant to) {
        String site = siteId == null ? "" : " AND site_id = :site";
        var spec = jdbc.sql("""
                        SELECT device_id, site_id, min_value, max_value, sum_value, sample_count, first_at, first_value, last_at, last_value
                          FROM sdp.metric_rollups_hourly
                         WHERE metric = :metric AND hour >= :rolledFrom AND hour < :rolledTo%s
                        UNION ALL
                        SELECT device_id, max(site_id), min(v), max(v), sum(v), count(*),
                               min(measured_at), (array_agg(v ORDER BY measured_at))[1],
                               max(measured_at), (array_agg(v ORDER BY measured_at DESC))[1]
                          FROM (SELECT device_id, site_id, measured_at, (metrics ->> :metric)::float8 AS v
                                  FROM sdp.measurements
                                 WHERE ((measured_at >= :from AND measured_at < :rawUntil) OR (measured_at >= :rawFrom AND measured_at < :to))
                                   AND jsonb_typeof(metrics -> :metric) = 'number'%s) raw
                         GROUP BY device_id""".formatted(site, site))
                .param("metric", metric)
                .param("from", Timestamp.from(from)).param("rawUntil", Timestamp.from(rawUntil))
                .param("rolledFrom", Timestamp.from(rolledFrom)).param("rolledTo", Timestamp.from(rolledTo))
                .param("rawFrom", Timestamp.from(rawFrom)).param("to", Timestamp.from(to));
        if (siteId != null) {
            spec = spec.param("site", siteId);
        }
        return spec.query((rs, row) -> new Partial(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                rs.getDouble(5), rs.getLong(6), rs.getObject(7, OffsetDateTime.class).toInstant(), rs.getDouble(8),
                rs.getObject(9, OffsetDateTime.class).toInstant(), rs.getDouble(10))).list();
    }

    List<SiteStatus> status(String siteId, Instant reportingSince) {
        String site = siteId == null ? "" : " WHERE s.site_id = :site";
        var counts = jdbc.sql("""
                        SELECT s.site_id, s.display_name, count(d.device_id) AS devices,
                               count(d.device_id) FILTER (WHERE d.last_seen_at >= :since) AS reporting
                          FROM sdp.sites s LEFT JOIN sdp.devices d ON d.site_id = s.site_id%s
                         GROUP BY s.site_id, s.display_name
                         ORDER BY s.site_id""".formatted(site))
                .param("since", Timestamp.from(reportingSince));
        var silent = jdbc.sql("""
                        SELECT site_id, device_id, display_name, last_seen_at
                          FROM (SELECT site_id, device_id, display_name, last_seen_at,
                                       row_number() OVER (PARTITION BY site_id ORDER BY last_seen_at DESC, device_id) AS rank
                                  FROM sdp.devices s
                                 WHERE last_seen_at < :since%s) ranked
                         WHERE rank <= :limit
                         ORDER BY site_id, rank""".formatted(siteId == null ? "" : " AND s.site_id = :site"))
                .param("since", Timestamp.from(reportingSince)).param("limit", MAX_SILENT_DEVICES);
        if (siteId != null) {
            counts = counts.param("site", siteId);
            silent = silent.param("site", siteId);
        }
        Map<String, List<SiteStatus.SilentDevice>> silentBySite = new LinkedHashMap<>();
        silent.query((rs, row) -> Map.entry(rs.getString("site_id"), new SiteStatus.SilentDevice(rs.getString("device_id"),
                        rs.getString("display_name"), rs.getObject("last_seen_at", OffsetDateTime.class).toInstant())))
                .list()
                .forEach(e -> silentBySite.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return counts.query((rs, row) -> {
            long devices = rs.getLong("devices");
            long reporting = rs.getLong("reporting");
            return new SiteStatus(rs.getString("site_id"), rs.getString("display_name"), devices, reporting, devices - reporting,
                    silentBySite.getOrDefault(rs.getString("site_id"), List.of()));
        }).list();
    }
}
