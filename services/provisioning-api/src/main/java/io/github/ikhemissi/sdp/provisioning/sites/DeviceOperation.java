package io.github.ikhemissi.sdp.provisioning.sites;

import java.time.Instant;
import java.util.UUID;

/** Disabling or re-enabling the devices of a site in IoT Hub; retried by reconciliation until done. */
public record DeviceOperation(UUID id, String siteId, String kind, String status, int deviceCount, int attempts,
                              String lastError, Instant createdAt, Instant updatedAt) {

    public static final String DISABLE = "DISABLE_DEVICES";
    public static final String ENABLE = "ENABLE_DEVICES";
    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
}
