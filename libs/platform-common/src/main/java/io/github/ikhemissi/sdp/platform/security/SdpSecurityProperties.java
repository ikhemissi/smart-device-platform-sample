package io.github.ikhemissi.sdp.platform.security;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API security settings.
 *
 * @param enabled     whether the platform security auto-configuration applies
 * @param tenantId    Entra tenant whose v2 access tokens are accepted
 * @param audiences   accepted {@code aud} values (API client id and identifier URI)
 * @param jwkSetUri   signing keys location; defaults to the tenant's v2 discovery keys
 * @param publicPaths request patterns served without a token (health probes)
 */
@ConfigurationProperties("sdp.security")
public record SdpSecurityProperties(
        Boolean enabled, String tenantId, List<String> audiences, URI jwkSetUri, List<String> publicPaths) {

    public SdpSecurityProperties {
        enabled = enabled == null || enabled;
        audiences = audiences == null ? List.of() : audiences.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
        publicPaths = publicPaths == null ? List.of("/actuator/health", "/actuator/health/**") : List.copyOf(publicPaths);
    }

    public String issuer() {
        return "https://login.microsoftonline.com/" + tenantId + "/v2.0";
    }

    public URI effectiveJwkSetUri() {
        return jwkSetUri != null ? jwkSetUri : URI.create("https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys");
    }

    void validate() {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("sdp.security.tenant-id must be set (SDP_ENTRA_TENANT_ID)");
        }
        if (audiences.isEmpty()) {
            throw new IllegalStateException("sdp.security.audiences must list the API audiences (SDP_API_AUDIENCES)");
        }
    }
}
