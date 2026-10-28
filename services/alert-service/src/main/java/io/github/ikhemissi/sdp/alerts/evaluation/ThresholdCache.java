package io.github.ikhemissi.sdp.alerts.evaluation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.ikhemissi.sdp.alerts.AlertingProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Enabled thresholds per device, cached for {@code sdp.alerting.threshold-cache-ttl} (30 s) to keep database load low:
 * threshold changes are picked up within that delay. The evaluation itself always re-reads the locked threshold.
 */
@Component
public class ThresholdCache {

    /** A threshold watching a metric of the device. */
    public record Watched(UUID id, String metric) {
    }

    private final AlertStore store;
    private final Cache<String, List<Watched>> cache;

    public ThresholdCache(AlertStore store, AlertingProperties properties) {
        this.store = store;
        this.cache = Caffeine.newBuilder().expireAfterWrite(properties.thresholdCacheTtl()).maximumSize(100_000).build();
    }

    public List<Watched> thresholds(String deviceId) {
        return cache.get(deviceId, store::enabledThresholds);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
