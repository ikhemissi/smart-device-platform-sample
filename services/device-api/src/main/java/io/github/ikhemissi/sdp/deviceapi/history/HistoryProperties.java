package io.github.ikhemissi.sdp.deviceapi.history;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Telemetry history settings (M3 design D1/D2).
 *
 * @param retention          how long measurements are kept: metrics reported within it are listed
 * @param maxRange           longest range for raw measurements and series
 * @param partitions         Event Hubs partitions: the watermark is trusted once all of them have reported
 * @param watermarkMargin    subtracted from the watermark (IoT Hub enqueue time precedes Event Hubs enqueue time)
 * @param completeChunkTtl   cache lifetime of chunks that can no longer change
 * @param openChunkTtl       cache lifetime of chunks that may still receive measurements
 * @param latestTtl          cache lifetime of the latest measurements
 * @param metricsTtl         cache lifetime of a device's reported metrics
 */
@ConfigurationProperties("sdp.history")
public record HistoryProperties(
        @DefaultValue("30d") Duration retention,
        @DefaultValue("31d") Duration maxRange,
        @DefaultValue("4") int partitions,
        @DefaultValue("2m") Duration watermarkMargin,
        @DefaultValue("24h") Duration completeChunkTtl,
        @DefaultValue("30s") Duration openChunkTtl,
        @DefaultValue("10s") Duration latestTtl,
        @DefaultValue("5m") Duration metricsTtl) {
}
