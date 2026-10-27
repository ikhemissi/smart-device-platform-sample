package io.github.ikhemissi.sdp.deviceapi.devices;

import java.time.Instant;

/** A device in the list. {@code displayName} is {@code null} until an Editor names the device. */
public record DeviceSummary(String deviceId, String displayName, String siteId, String lastPayloadFormat,
                            Instant firstSeenAt, Instant lastSeenAt, DeviceStatus status) {
}
