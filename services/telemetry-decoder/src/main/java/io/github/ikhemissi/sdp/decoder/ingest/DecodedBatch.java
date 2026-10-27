package io.github.ikhemissi.sdp.decoder.ingest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Result of decoding one batch: what to store, and per-message outcomes for observability. */
public record DecodedBatch(List<Measurement> measurements, List<Rejection> rejections, List<Outcome> outcomes, int inBatchDuplicates) {

    /** A decoded measurement ready to be stored. */
    public record Measurement(
            String deviceId,
            String siteId,
            Instant measuredAt,
            Instant receivedAt,
            String messageKey,
            String payloadFormat,
            byte[] rawPayload,
            Map<String, BigDecimal> metrics,
            Map<String, String> attributes) {
    }

    /** A message set aside because it cannot be decoded or attributed. */
    public record Rejection(
            String deviceId,
            String siteId,
            Instant enqueuedAt,
            Instant receivedAt,
            String messageKey,
            String reason,
            String detail,
            byte[] rawPayload) {

        /** Device id used for deduplication; empty when the device is unknown. */
        public String ledgerDeviceId() {
            return deviceId == null ? "" : deviceId;
        }
    }

    /** Per-message decoding outcome, before deduplication against previously stored messages. */
    public record Outcome(TelemetryMessage message, String payloadFormat, String rejectionReason) {

        public boolean rejected() {
            return rejectionReason != null;
        }
    }
}
