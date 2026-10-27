package io.github.ikhemissi.sdp.deviceapi.devices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** A device row. The entity tag covers the editable fields only, so telemetry updating {@code last_seen_at} does not invalidate an edit in progress. */
record DeviceRecord(String deviceId, String siteId, String displayName, String description, Location location,
                    String lastPayloadFormat, Instant firstSeenAt, Instant lastSeenAt, Instant updatedAt,
                    String updatedBy, String updatedByName) {

    String entityTag() {
        String state = String.join("\u0000", Objects.toString(displayName, ""), Objects.toString(description, ""),
                Objects.toString(location, ""), Objects.toString(updatedAt, ""));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest, 0, 12) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    DeviceStatus status(Instant reportingSince) {
        return lastSeenAt.isBefore(reportingSince) ? DeviceStatus.SILENT : DeviceStatus.REPORTING;
    }

    DeviceSummary toSummary(Instant reportingSince) {
        return new DeviceSummary(deviceId, displayName, siteId, lastPayloadFormat, firstSeenAt, lastSeenAt,
                status(reportingSince));
    }

    DeviceDetails toDetails(Instant reportingSince) {
        return new DeviceDetails(deviceId, displayName, siteId, lastPayloadFormat, firstSeenAt, lastSeenAt,
                status(reportingSince), description, location, updatedAt,
                updatedBy == null ? null : new Editor(updatedBy, updatedByName));
    }
}
