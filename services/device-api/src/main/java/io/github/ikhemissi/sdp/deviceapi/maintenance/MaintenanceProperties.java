package io.github.ikhemissi.sdp.deviceapi.maintenance;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Maintenance in this environment (M9 design D8).
 *
 * @param enabled whether the maintenance job runs
 * @param sites   assessed sites; empty: every site
 */
@ConfigurationProperties("sdp.maintenance")
public record MaintenanceProperties(boolean enabled, List<String> sites) {

    public MaintenanceProperties {
        sites = sites == null ? List.of() : sites.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    boolean assesses(String siteId) {
        return enabled && (sites.isEmpty() || sites.contains(siteId));
    }
}
