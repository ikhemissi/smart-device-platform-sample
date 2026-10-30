package io.github.ikhemissi.sdp.deviceapi.anomalies;

import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredRole(SdpRole.READ)
@Tag(name = "Anomalies")
public class AnomalyController {

    private static final String STATUS_DESCRIPTION = "Statuses to list, repeated or comma-separated: confirmed, to_review, "
            + "dismissed, reviewed, pending.";

    private final AnomalyService service;

    public AnomalyController(AnomalyService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/anomalies")
    @Operation(operationId = "listAnomalies", summary = "Anomalies of the analysed sites",
            description = "Anomalies overlapping the period between from (default: 7 days before to) and to (default: now), "
                    + "most recent first, filtered by site, device and status (default: confirmed and to_review, the "
                    + "anomalies to act on).")
    public List<Anomaly> anomalies(
            @RequestParam(required = false) @Size(min = 1, max = 40) String siteId,
            @RequestParam(required = false) @Size(min = 1, max = 128) String deviceId,
            @RequestParam(required = false) @Parameter(description = STATUS_DESCRIPTION,
                    array = @ArraySchema(schema = @Schema(allowableValues = {"confirmed", "to_review", "dismissed", "reviewed",
                            "pending"}))) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") @Min(value = 1, message = "must be at least 1")
            @Max(value = 200, message = "must be at most 200") int limit) {
        return service.anomalies(siteId, deviceId, status, from, to, limit);
    }

    @GetMapping("/api/v1/devices/{deviceId}/anomalies")
    @Operation(operationId = "listDeviceAnomalies", summary = "Anomalies of a device",
            description = "The device's anomalies overlapping the period (default: the last 7 days), most recent first. "
                    + "Default statuses: confirmed, to_review and reviewed.")
    public List<Anomaly> deviceAnomalies(
            @PathVariable String deviceId,
            @RequestParam(required = false) @Parameter(description = STATUS_DESCRIPTION,
                    array = @ArraySchema(schema = @Schema(allowableValues = {"confirmed", "to_review", "dismissed", "reviewed",
                            "pending"}))) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") @Min(value = 1, message = "must be at least 1")
            @Max(value = 200, message = "must be at most 200") int limit) {
        return service.deviceAnomalies(deviceId, status, from, to, limit);
    }

    @PostMapping("/api/v1/anomalies/{anomalyId}/review")
    @RequiredRole(SdpRole.WRITE)
    @Operation(operationId = "reviewAnomaly", summary = "Review an anomaly",
            description = "Records whether the automated classification is correct, with an optional comment (at most 1,000 "
                    + "characters). The reviewer is the signed-in Editor; a new review replaces the previous one. The anomaly's "
                    + "status becomes reviewed.")
    public Anomaly review(@PathVariable long anomalyId, @Valid @RequestBody ReviewInput input) {
        return service.review(anomalyId, input, SdpUser.current());
    }
}
