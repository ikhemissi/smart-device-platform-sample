package io.github.ikhemissi.sdp.deviceapi.alerts;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An alert: one episode of a device's metric outside its threshold, with the threshold as it was when it opened.
 *
 * @param status {@code OPEN}, {@code CLOSED} (back to normal) or {@code CANCELLED} (threshold disabled or deleted)
 */
@Schema(name = "Alert")
public record Alert(
        long id,
        @Schema(nullable = true, description = "Null once the threshold is deleted") UUID thresholdId,
        String deviceId,
        @Schema(nullable = true) String deviceName,
        String siteId,
        String metric,
        @Schema(nullable = true) String unit,
        @Schema(nullable = true) BigDecimal thresholdMin,
        @Schema(nullable = true) BigDecimal thresholdMax,
        @Schema(allowableValues = {"ABOVE", "BELOW"}) String direction,
        @Schema(allowableValues = {"OPEN", "CLOSED", "CANCELLED"}) String status,
        Instant openedAt,
        @Schema(nullable = true) Instant closedAt,
        BigDecimal firstValue,
        BigDecimal worstValue,
        BigDecimal lastValue,
        Instant lastBreachAt,
        List<Notification> notifications) {

    /** An email of the alert: the alert itself, an hourly reminder, or the back-to-normal message. */
    @Schema(name = "AlertNotification")
    public record Notification(
            @Schema(allowableValues = {"ALERT", "REMINDER", "RESOLVED"}) String kind,
            @Schema(allowableValues = {"PENDING", "SENT", "FAILED"}) String status,
            int recipients,
            int attempts,
            Instant createdAt,
            @Schema(nullable = true) Instant sentAt,
            @Schema(nullable = true) String lastError) {
    }
}
