package io.github.ikhemissi.sdp.deviceapi.devices;

import io.github.ikhemissi.sdp.platform.security.RequiredRole;
import io.github.ikhemissi.sdp.platform.security.SdpRole;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredRole(SdpRole.READ)
@Tag(name = "Devices")
public class DeviceController {

    static final String MERGE_PATCH = "application/merge-patch+json";

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listDevices", summary = "List devices",
            description = "Filter by site and status, search id and display name (case-insensitive), sort and page.")
    public DevicePage list(
            @RequestParam(required = false) @Size(min = 1, max = 40) String siteId,
            @RequestParam(required = false) @Pattern(regexp = "reporting|silent", message = "must be reporting or silent")
            @Parameter(schema = @Schema(allowableValues = {"reporting", "silent"})) String status,
            @RequestParam(required = false) @Size(max = 100, message = "must be at most 100 characters") String search,
            @RequestParam(defaultValue = "displayName")
            @Pattern(regexp = "displayName|siteId|firstSeen|lastSeen", message = "must be displayName, siteId, firstSeen or lastSeen")
            @Parameter(schema = @Schema(allowableValues = {"displayName", "siteId", "firstSeen", "lastSeen"})) String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc", message = "must be asc or desc")
            @Parameter(schema = @Schema(allowableValues = {"asc", "desc"})) String order,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "must be at least 1") int page,
            @RequestParam(defaultValue = "25") @Min(value = 1, message = "must be at least 1")
            @Max(value = 100, message = "must be at most 100") int pageSize) {
        String term = search == null || search.isBlank() ? null : search.strip();
        return service.list(new DeviceQuery(siteId, DeviceStatus.parse(status), term,
                DeviceQuery.SortField.parse(sort), "desc".equals(order), page, pageSize));
    }

    @GetMapping("/{deviceId}")
    @Operation(operationId = "getDevice", summary = "Get a device", description = "The ETag header is required to edit it.")
    public ResponseEntity<DeviceDetails> get(@PathVariable String deviceId) {
        DeviceService.Versioned versioned = service.get(deviceId);
        return ResponseEntity.ok().eTag(versioned.entityTag()).body(versioned.device());
    }

    @PatchMapping(path = "/{deviceId}", consumes = {MERGE_PATCH, "application/json"})
    @RequiredRole(SdpRole.WRITE)
    @Operation(operationId = "updateDevice", summary = "Edit a device's information",
            description = "JSON Merge Patch of displayName, description and location; requires If-Match with the current ETag.")
    public ResponseEntity<DeviceDetails> update(
            @PathVariable String deviceId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody @Schema(implementation = DeviceEdit.class) JsonNode patch) {
        DeviceService.Versioned versioned = service.update(deviceId, ifMatch, patch, SdpUser.current());
        return ResponseEntity.ok().eTag(versioned.entityTag()).body(versioned.device());
    }

    /** Documentation of the patch body (the body is parsed as a merge patch to distinguish absent from null). */
    @Schema(name = "DeviceEdit", description = "Fields to change; null clears a field, absent fields are unchanged.")
    record DeviceEdit(@Schema(maxLength = 100, nullable = true) String displayName,
                      @Schema(maxLength = 1000, nullable = true) String description,
                      @Schema(nullable = true) LocationEdit location) {
    }

    @Schema(name = "LocationEdit")
    record LocationEdit(@Schema(minimum = "-90", maximum = "90") Double latitude,
                        @Schema(minimum = "-180", maximum = "180") Double longitude,
                        @Schema(maxLength = 100, nullable = true) String label) {
    }
}
