package io.github.ikhemissi.sdp.deviceapi.maintenance;

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
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredRole(SdpRole.READ)
@Tag(name = "Maintenance")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/maintenance")
    @Operation(operationId = "listMaintenance", summary = "Maintenance queue",
            description = "Items of the assessed sites, most likely first, filtered by site and status (default: open and "
                    + "planned, the queue). Empty when maintenance is off in the environment.")
    public List<MaintenanceItem> items(
            @RequestParam(required = false) @Size(min = 1, max = 40) String siteId,
            @RequestParam(required = false) @Parameter(description = "Statuses to list, repeated or comma-separated: open, "
                    + "planned, done, resolved.", array = @ArraySchema(schema = @Schema(allowableValues = {"open", "planned",
                    "done", "resolved"}))) List<String> status,
            @RequestParam(defaultValue = "100") @Min(value = 1, message = "must be at least 1")
            @Max(value = 200, message = "must be at most 200") int limit) {
        return service.items(siteId, status, limit);
    }

    @GetMapping("/api/v1/devices/{deviceId}/maintenance")
    @Operation(operationId = "getDeviceMaintenance", summary = "Maintenance of a device",
            description = "Whether the device is assessed, its latest assessment (battery forecast, reporting signals, "
                    + "probability of an intervention within 7 days) and its open or planned item.")
    public DeviceMaintenance device(@PathVariable String deviceId) {
        return service.device(deviceId);
    }

    @PostMapping("/api/v1/maintenance/{itemId}/status")
    @RequiredRole(SdpRole.WRITE)
    @Operation(operationId = "setMaintenanceStatus", summary = "Plan or close a maintenance item",
            description = "Marks an open or planned item planned or done, with an optional comment (at most 1,000 characters), "
                    + "by the signed-in Editor. A done item leaves the queue and the device is not queued again for 7 days. "
                    + "409 for a done or resolved item.")
    public MaintenanceItem setStatus(@PathVariable long itemId, @Valid @RequestBody StatusInput input) {
        return service.setStatus(itemId, input, SdpUser.current());
    }
}
