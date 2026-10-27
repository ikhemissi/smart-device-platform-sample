package io.github.ikhemissi.sdp.deviceapi.devices;

import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import io.github.ikhemissi.sdp.platform.security.SdpUser;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class DeviceService {

    private final DeviceRepository repository;
    private final DevicesProperties properties;
    private final Clock clock;

    public DeviceService(DeviceRepository repository, DevicesProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DevicePage list(DeviceQuery query) {
        Instant since = reportingSince();
        var items = repository.find(query, since).stream().map(device -> device.toSummary(since)).toList();
        return new DevicePage(items, query.page(), query.pageSize(), repository.count(query, since));
    }

    public Versioned get(String deviceId) {
        DeviceRecord device = repository.findById(deviceId).orElseThrow(() -> notFound(deviceId));
        return new Versioned(device.toDetails(reportingSince()), device.entityTag());
    }

    @Transactional
    public Versioned update(String deviceId, String ifMatch, JsonNode patch, SdpUser editor) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED,
                    "Send the device's current entity tag in If-Match to avoid overwriting a concurrent change.");
        }
        DeviceRecord current = repository.lockById(deviceId).orElseThrow(() -> notFound(deviceId));
        if (!matches(ifMatch, current.entityTag())) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED,
                    "The device was changed by someone else; reload it and apply your change again.");
        }
        DevicePatch.Result changes = DevicePatch.apply(patch, current);
        // Microsecond precision, as stored by PostgreSQL, so the entity tag computed now matches later reads.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        repository.update(deviceId, changes.displayName(), changes.description(), changes.location(), now,
                editor.objectId(), editor.displayName());
        DeviceRecord updated = repository.findById(deviceId).orElseThrow(() -> notFound(deviceId));
        return new Versioned(updated.toDetails(reportingSince()), updated.entityTag());
    }

    private Instant reportingSince() {
        return clock.instant().minus(properties.reportingWindow());
    }

    static boolean matches(String ifMatch, String entityTag) {
        return Arrays.stream(ifMatch.split(","))
                .map(String::strip)
                .map(tag -> tag.startsWith("W/") ? tag.substring(2) : tag)
                .anyMatch(tag -> tag.equals("*") || tag.equals(entityTag));
    }

    private static ApiException notFound(String deviceId) {
        return ApiException.notFound("Device '" + deviceId + "' does not exist.");
    }

    /** A device with its entity tag. */
    public record Versioned(DeviceDetails device, String entityTag) {
    }
}
