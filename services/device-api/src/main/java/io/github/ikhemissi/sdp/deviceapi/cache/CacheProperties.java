package io.github.ikhemissi.sdp.deviceapi.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * History cache settings (M3 design D2).
 *
 * @param host             Azure Managed Redis host name; the cache is disabled when empty
 * @param port             TLS port (Azure Managed Redis: 10000)
 * @param commandTimeout   a slower cache is treated as a miss
 * @param failureThreshold consecutive failures that open the circuit breaker
 * @param openDuration     how long the cache is bypassed once the breaker opens
 * @param keyPrefix        prefix (and version) of every key
 */
@ConfigurationProperties("sdp.cache")
public record CacheProperties(
        String host,
        @DefaultValue("10000") int port,
        @DefaultValue("100ms") Duration commandTimeout,
        @DefaultValue("5") int failureThreshold,
        @DefaultValue("30s") Duration openDuration,
        @DefaultValue("sdp:v1:") String keyPrefix) {

    public boolean enabled() {
        return host != null && !host.isBlank();
    }
}
