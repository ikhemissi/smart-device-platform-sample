package io.github.ikhemissi.sdp.deviceapi.history;

import java.time.Instant;
import java.util.Map;

/** One stored measurement: decoded metrics and attributes as reported by the device. */
public record Measurement(Instant measuredAt, String payloadFormat, Map<String, Object> metrics, Map<String, String> attributes) {
}
