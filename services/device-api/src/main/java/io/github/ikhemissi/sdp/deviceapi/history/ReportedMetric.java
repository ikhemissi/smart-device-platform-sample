package io.github.ikhemissi.sdp.deviceapi.history;

import java.time.Instant;

/** A metric a device has reported within the retention period, with its unit and description. */
public record ReportedMetric(String metric, String unit, String description, Instant lastSeenAt) {
}
