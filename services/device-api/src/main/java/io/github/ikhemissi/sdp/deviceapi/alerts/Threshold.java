package io.github.ikhemissi.sdp.deviceapi.alerts;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Expected range of one metric of a device; a measurement outside it opens an alert emailed to the recipients.
 *
 * @param etag entity tag to send in {@code If-Match} when changing the threshold
 */
@Schema(name = "Threshold")
public record Threshold(
        UUID id,
        String deviceId,
        String metric,
        @Schema(nullable = true) String unit,
        @Schema(nullable = true) BigDecimal min,
        @Schema(nullable = true) BigDecimal max,
        List<String> recipients,
        boolean enabled,
        @Schema(description = "Entity tag to send in If-Match when changing the threshold") String etag,
        Instant updatedAt,
        @Schema(nullable = true) String updatedByName,
        @Schema(nullable = true, description = "Id of the threshold's open alert, if any") Long openAlertId) {

    static String entityTag(UUID id, int version) {
        return "\"" + id + "." + version + "\"";
    }
}
