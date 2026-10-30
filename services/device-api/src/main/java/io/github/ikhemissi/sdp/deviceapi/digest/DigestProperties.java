package io.github.ikhemissi.sdp.deviceapi.digest;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Site digests in this environment (M8 design D6).
 *
 * @param enabled whether the digest job runs
 * @param sites   sites that get digests; empty: every site
 */
@ConfigurationProperties("sdp.digest")
public record DigestProperties(boolean enabled, List<String> sites) {

    public DigestProperties {
        sites = sites == null ? List.of() : sites.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    boolean digests(String siteId) {
        return enabled && (sites.isEmpty() || sites.contains(siteId));
    }
}
