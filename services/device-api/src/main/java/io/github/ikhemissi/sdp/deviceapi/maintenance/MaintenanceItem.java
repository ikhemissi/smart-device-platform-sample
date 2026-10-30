package io.github.ikhemissi.sdp.deviceapi.maintenance;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * An item of the maintenance queue (spec predictive-maintenance): a device likely to need an on-site intervention within 7
 * days, with the reasons, the battery forecast and the work order computed by the platform, and the Editors' status.
 */
@Schema(name = "MaintenanceItem")
public record MaintenanceItem(
        long id,
        String deviceId,
        @Schema(nullable = true) String deviceName,
        String siteId,
        @Schema(allowableValues = {"open", "planned", "done", "resolved"},
                description = "open and planned items are in the queue; done (by an Editor) and resolved (by the platform, "
                        + "when the probability fell below the confidence) close an item") String status,
        @Schema(description = "Probability of an intervention within 7 days, from the decision model") double probability,
        @JsonRawValue
        @Schema(implementation = Object.class, description = "The computed figures that crossed a rule (rule, value, threshold, "
                + "unit), or the battery forecast and the probability when none did")
        String reasons,
        @JsonRawValue
        @Schema(implementation = Object.class, nullable = true, description = "Battery forecast (metric, unit, level, "
                + "slope_per_day, days_left, since, cutoff); null when the device reports no battery")
        String battery,
        @Schema(nullable = true, description = "Null until the language model wrote it; a later run retries") String workOrder,
        @Schema(nullable = true) String workOrderModel,
        @Schema(nullable = true) Instant workOrderAt,
        Instant openedAt,
        Instant updatedAt,
        @Schema(nullable = true) Instant resolvedAt,
        @Schema(nullable = true, description = "The Editor who set the status") String statusBy,
        @Schema(nullable = true) Instant statusAt,
        @Schema(nullable = true) String statusComment) {
}
