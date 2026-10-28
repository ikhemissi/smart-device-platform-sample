package io.github.ikhemissi.sdp.decoder;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Decoder configuration. In Azure, Event Hubs and the checkpoint container are addressed by endpoint and accessed
 * with the workload's managed identity. Connection strings are accepted only for local emulators (Event Hubs
 * emulator, Azurite).
 */
@ConfigurationProperties("sdp.decoder")
public record DecoderProperties(
        @DefaultValue EventHubs eventhubs,
        @DefaultValue Checkpoint checkpoint,
        @DefaultValue Batch batch,
        @DefaultValue Retry retry,
        @DefaultValue Publication publication) {

    /**
     * @param fullyQualifiedNamespace e.g. {@code evhns-demo.servicebus.windows.net}
     * @param connectionString        Event Hubs emulator only
     */
    public record EventHubs(
            String fullyQualifiedNamespace,
            @DefaultValue("device-telemetry") String eventHubName,
            @DefaultValue("telemetry-decoder") String consumerGroup,
            String connectionString) {
    }

    /**
     * @param containerUrl     e.g. {@code https://stdemo.blob.core.windows.net/checkpoints}
     * @param connectionString Azurite only (used with {@code containerName})
     */
    public record Checkpoint(
            String containerUrl,
            String connectionString,
            @DefaultValue("checkpoints") String containerName) {
    }

    public record Batch(@DefaultValue("500") int maxSize, @DefaultValue("5s") Duration maxWait) {
    }

    /**
     * Publication of persisted measurements to the Service Bus topic (alerting, ADR-0009).
     *
     * @param fullyQualifiedNamespace e.g. {@code sbns-demo.servicebus.windows.net}
     * @param connectionString        Service Bus emulator only
     */
    public record Publication(
            @DefaultValue("false") boolean enabled,
            String fullyQualifiedNamespace,
            @DefaultValue("measurements") String topic,
            String connectionString) {
    }

    public record Retry(@DefaultValue("1s") Duration initialBackoff, @DefaultValue("30s") Duration maxBackoff) {
    }
}
