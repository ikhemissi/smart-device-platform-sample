package io.github.ikhemissi.sdp.deviceapi.sites;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SiteRepository {

    private final JdbcClient jdbc;

    public SiteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Site> sitesWithDeviceCounts() {
        return jdbc.sql("""
                        SELECT s.site_id, s.display_name, count(d.device_id) AS device_count
                          FROM sdp.sites s LEFT JOIN sdp.devices d ON d.site_id = s.site_id
                         GROUP BY s.site_id, s.display_name
                         ORDER BY s.site_id""")
                .query((rs, row) -> new Site(rs.getString("site_id"), rs.getString("display_name"), rs.getLong("device_count")))
                .list();
    }
}
