package io.github.ikhemissi.sdp.deviceapi.anomalies;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/**
 * An anomaly found by the anomaly detector (spec anomaly-detection): one episode of a device's metric far from its usual
 * values, with the decision model's classification, every class probability and, when confirmed, an explanation.
 *
 * @param status {@code reviewed} once an Editor reviewed it, otherwise the automated decision
 */
@Schema(name = "Anomaly")
public record Anomaly(
        long id,
        String deviceId,
        @Schema(nullable = true) String deviceName,
        String siteId,
        String metric,
        @Schema(nullable = true) String unit,
        @Schema(allowableValues = {"deviation", "flat", "jump"}) String kind,
        Instant startedAt,
        Instant endedAt,
        double observedMin,
        double observedMax,
        double observedMean,
        double observedLast,
        @Schema(description = "The device's usual value at that time of day") double usualValue,
        @Schema(description = "Typical spread of the device's values around the usual value") double spread,
        @Schema(description = "Distance from the usual value, in spreads (signed)") double score,
        @Schema(allowableValues = {"pending", "confirmed", "to_review", "dismissed", "reviewed"}) String status,
        @Schema(allowableValues = {"pending", "confirmed", "to_review", "dismissed"}) String decision,
        @Schema(nullable = true, allowableValues = {"real_event", "sensor_fault", "expected_pattern", "cannot_tell"})
        String classification,
        @Schema(nullable = true, description = "Probability of the classification") Double confidence,
        @Schema(description = "Probability of each class (empty while pending)") Map<String, Double> probabilities,
        @Schema(nullable = true, description = "Confidence threshold applied to the decision") Double confidenceThreshold,
        @Schema(nullable = true) String decisionModel,
        @Schema(nullable = true) String decisionModelVersion,
        @Schema(nullable = true) String explanation,
        @Schema(nullable = true) String explanationModel,
        Instant detectedAt,
        Instant updatedAt,
        @Schema(nullable = true) Review review) {

    /** An Editor's verdict on the automated classification. */
    @Schema(name = "AnomalyReview")
    public record Review(
            Instant reviewedAt,
            String reviewedBy,
            @Schema(allowableValues = {"correct", "incorrect"}) String verdict,
            @Schema(nullable = true) String comment) {
    }
}
