package io.github.ikhemissi.sdp.deviceapi.sites;

import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Sites")
public class SiteController {

    private final JdbcClient jdbc;

    public SiteController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/sites")
    @RequiredRole(SdpRole.READ)
    @Operation(operationId = "listSites", summary = "List sites with their number of devices")
    public List<Site> list() {
        return jdbc.sql("""
                        SELECT s.site_id, s.display_name, count(d.device_id) AS device_count
                          FROM sdp.sites s LEFT JOIN sdp.devices d ON d.site_id = s.site_id
                         GROUP BY s.site_id, s.display_name
                         ORDER BY s.site_id""")
                .query((rs, row) -> new Site(rs.getString("site_id"), rs.getString("display_name"), rs.getLong("device_count")))
                .list();
    }
}
