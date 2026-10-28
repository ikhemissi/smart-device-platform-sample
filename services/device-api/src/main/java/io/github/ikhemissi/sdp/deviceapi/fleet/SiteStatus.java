package io.github.ikhemissi.sdp.deviceapi.fleet;

import java.time.Instant;
import java.util.List;

/**
 * Device counts of a site (spec fleet-analytics). A device is reporting when last seen within the reporting window
 * (default 15 minutes), silent otherwise.
 *
 * @param silentDevices the most recently seen silent devices first, at most 100
 */
public record SiteStatus(String siteId, String displayName, long devices, long reporting, long silent,
                         List<SilentDevice> silentDevices) {

    public record SilentDevice(String deviceId, String displayName, Instant lastSeenAt) {
    }
}
