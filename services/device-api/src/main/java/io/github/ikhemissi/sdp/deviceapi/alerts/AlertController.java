package io.github.ikhemissi.sdp.deviceapi.alerts;

import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredRole(SdpRole.READ)
@Tag(name = "Alerting")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/devices/{deviceId}/thresholds")
    @Operation(operationId = "listThresholds", summary = "Thresholds of a device")
    public List<Threshold> thresholds(@PathVariable String deviceId) {
        return service.thresholds(deviceId);
    }

    @PostMapping("/api/v1/devices/{deviceId}/thresholds")
    @RequiredRole(SdpRole.WRITE)
    @Operation(operationId = "createThreshold", summary = "Add a threshold to a device",
            description = "A metric the device reports, a minimum and/or a maximum (min < max), and 1 to 10 email recipients; "
                    + "at most 20 thresholds per device. Changes are applied by the alert service within 30 seconds.")
    public ResponseEntity<Threshold> create(@PathVariable String deviceId, @Valid @RequestBody ThresholdInput input) {
        Threshold created = service.create(deviceId, input, SdpUser.current());
        return ResponseEntity.created(URI.create("/api/v1/devices/" + deviceId + "/thresholds/" + created.id()))
                .eTag(created.etag()).body(created);
    }

    @PutMapping("/api/v1/devices/{deviceId}/thresholds/{thresholdId}")
    @RequiredRole(SdpRole.WRITE)
    @Operation(operationId = "updateThreshold", summary = "Change, enable or disable a threshold",
            description = "Replaces the threshold; requires If-Match with its entity tag. Disabling it, or changing its "
                    + "metric, closes its open alert without notification.")
    public ResponseEntity<Threshold> update(
            @PathVariable String deviceId, @PathVariable UUID thresholdId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ThresholdInput input) {
        Threshold updated = service.replace(deviceId, thresholdId, ifMatch, input, SdpUser.current());
        return ResponseEntity.ok().eTag(updated.etag()).body(updated);
    }

    @DeleteMapping("/api/v1/devices/{deviceId}/thresholds/{thresholdId}")
    @RequiredRole(SdpRole.WRITE)
    @Operation(operationId = "deleteThreshold", summary = "Delete a threshold",
            description = "Closes its open alert without notification; past alerts are kept. If-Match is optional.")
    public ResponseEntity<Void> delete(@PathVariable String deviceId, @PathVariable UUID thresholdId,
                                       @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        service.delete(deviceId, thresholdId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/devices/{deviceId}/alerts")
    @Operation(operationId = "listDeviceAlerts", summary = "Alerts of a device",
            description = "Alerts open at some point between from (default: 30 days ago) and to (default: now), most recent "
                    + "first, with the threshold as it was and the notifications sent.")
    public List<Alert> deviceAlerts(
            @PathVariable String deviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") @Min(value = 1, message = "must be at least 1")
            @Max(value = 500, message = "must be at most 500") int limit) {
        return service.deviceAlerts(deviceId, from, to, limit);
    }

    @GetMapping("/api/v1/alerts")
    @Operation(operationId = "listAlerts", summary = "Alerts of all devices",
            description = "Filter by status and site; alerts open at some point between from (default: 30 days ago) and to "
                    + "(default: now), most recent first.")
    public List<Alert> alerts(
            @RequestParam(required = false) @Pattern(regexp = "(?i)open|closed|cancelled", message = "must be open, closed or cancelled")
            @Parameter(schema = @Schema(allowableValues = {"open", "closed", "cancelled"})) String status,
            @RequestParam(required = false) @Size(min = 1, max = 40) String siteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") @Min(value = 1, message = "must be at least 1")
            @Max(value = 500, message = "must be at most 500") int limit) {
        return service.alerts(status, siteId, from, to, limit);
    }
}
