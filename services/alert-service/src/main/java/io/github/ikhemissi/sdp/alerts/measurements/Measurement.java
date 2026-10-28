package io.github.ikhemissi.sdp.alerts.measurements;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A measurement published by the decoder (ADR-0009, schema version 1): JSON body with the device, site, payload
 * format, measurement time, the device's message key and the decoded metrics.
 */
public record Measurement(String deviceId, String siteId, String payloadFormat, Instant measuredAt, Instant receivedAt,
                          String messageKey, Map<String, BigDecimal> metrics) {

    public static final int SCHEMA_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Thrown for a message that can never be processed (dead-lettered rather than retried). */
    public static final class InvalidMeasurementException extends RuntimeException {
        InvalidMeasurementException(String message) {
            super(message);
        }
    }

    public static Measurement parse(byte[] body) {
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (RuntimeException e) {
            throw new InvalidMeasurementException("not JSON: " + e.getMessage());
        }
        int version = node.path("schemaVersion").asInt(0);
        if (version != SCHEMA_VERSION) {
            throw new InvalidMeasurementException("unsupported schema version " + version);
        }
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        node.path("metrics").properties().forEach(e -> {
            if (e.getValue().isNumber()) {
                metrics.put(e.getKey(), e.getValue().decimalValue());
            }
        });
        String deviceId = text(node, "deviceId");
        String messageKey = text(node, "messageKey");
        try {
            return new Measurement(deviceId, text(node, "siteId"), node.path("payloadFormat").asString(null),
                    Instant.parse(text(node, "measuredAt")),
                    node.hasNonNull("receivedAt") ? Instant.parse(node.get("receivedAt").asString()) : null,
                    messageKey, metrics);
        } catch (java.time.format.DateTimeParseException e) {
            throw new InvalidMeasurementException("invalid time: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asString(null);
        if (value == null || value.isBlank()) {
            throw new InvalidMeasurementException("missing " + field);
        }
        return value;
    }
}
