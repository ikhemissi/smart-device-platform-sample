package io.github.ikhemissi.sdp.decoder.ingest;

import java.time.Instant;

/**
 * A device message as received from Event Hubs (routed by IoT Hub).
 *
 * @param deviceId       IoT Hub device id ({@code iothub-connection-device-id}); null if absent
 * @param siteId         site added by IoT Hub message enrichment ({@code siteId} property); null if absent
 * @param enqueuedAt     time IoT Hub received the message ({@code iothub-enqueuedtime}), the measurement time
 * @param messageKey     device message id, or {@code <partition>:<sequenceNumber>} when the device set none
 * @param payload        raw binary payload
 * @param traceparent    W3C trace context propagated by the device; null if absent
 */
public record TelemetryMessage(
        String deviceId,
        String siteId,
        Instant enqueuedAt,
        String messageKey,
        byte[] payload,
        String traceparent) {
}
