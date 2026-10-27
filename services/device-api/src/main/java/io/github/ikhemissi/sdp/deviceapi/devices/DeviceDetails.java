package io.github.ikhemissi.sdp.deviceapi.devices;

import java.time.Instant;

/** A device with its editable information and the last change attribution. */
public record DeviceDetails(String deviceId, String displayName, String siteId, String lastPayloadFormat,
                            Instant firstSeenAt, Instant lastSeenAt, DeviceStatus status, String description,
                            Location location, Instant updatedAt, Editor updatedBy) {
}
