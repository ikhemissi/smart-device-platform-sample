package io.github.ikhemissi.sdp.deviceapi.maintenance;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A device's maintenance status: whether it is assessed, its latest assessment and its item in the queue. */
@Schema(name = "DeviceMaintenance")
public record DeviceMaintenance(
        String deviceId,
        @Schema(description = "Whether the device is assessed (maintenance on and its site configured)") boolean enabled,
        @Schema(nullable = true, description = "The latest assessment; null when there is none yet") Assessment assessment,
        @Schema(nullable = true, description = "The device's open or planned item, if any") MaintenanceItem item) {

    /** The latest assessment of the device by the maintenance job. */
    @Schema(name = "MaintenanceAssessment")
    public record Assessment(
            Instant assessedAt,
            @JsonRawValue
            @Schema(implementation = Object.class, nullable = true, description = "Battery forecast (metric, unit, level, "
                    + "slope_per_day, days_left, since, cutoff); null when the device reports no battery")
            String battery,
            @JsonRawValue
            @Schema(implementation = Object.class, description = "Reporting signals: minutes since the last message, messages, "
                    + "median interval and longest gap over the last 24 hours and the 7 days before, rejected messages")
            String signals,
            @Schema(description = "Probability of an intervention within 7 days") double probability,
            String decisionModel,
            String decisionModelVersion) {
    }
}
