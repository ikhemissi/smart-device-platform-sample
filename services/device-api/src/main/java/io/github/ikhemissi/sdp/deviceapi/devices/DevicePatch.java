package io.github.ikhemissi.sdp.deviceapi.devices;

import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * JSON Merge Patch (RFC 7396) restricted to the editable device information: {@code displayName},
 * {@code description} and {@code location} ({@code latitude}, {@code longitude}, {@code label}). A {@code null} value
 * clears the field; blank strings are treated as {@code null}.
 */
final class DevicePatch {

    static final int DISPLAY_NAME_MAX = 100;
    static final int DESCRIPTION_MAX = 1000;
    static final int LABEL_MAX = 100;
    private static final Set<String> EDITABLE = Set.of("displayName", "description", "location");
    private static final Set<String> LOCATION_FIELDS = Set.of("latitude", "longitude", "label");

    record Result(String displayName, String description, Location location) {
    }

    private DevicePatch() {
    }

    static Result apply(JsonNode patch, DeviceRecord current) {
        Map<String, List<String>> errors = new TreeMap<>();
        if (patch == null || !patch.isObject()) {
            throw ApiException.invalid(Map.of("body", List.of("must be a JSON object")));
        }
        for (String field : patch.propertyNames()) {
            if (!EDITABLE.contains(field)) {
                error(errors, field, "is not editable");
            }
        }
        String displayName = patch.has("displayName")
                ? text(patch.get("displayName"), "displayName", DISPLAY_NAME_MAX, errors) : current.displayName();
        String description = patch.has("description")
                ? text(patch.get("description"), "description", DESCRIPTION_MAX, errors) : current.description();
        Location location = patch.has("location") ? location(patch.get("location"), current.location(), errors) : current.location();
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        return new Result(displayName, description, location);
    }

    private static String text(JsonNode node, String field, int max, Map<String, List<String>> errors) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            error(errors, field, "must be a string");
            return null;
        }
        String value = node.asString().strip();
        if (value.length() > max) {
            error(errors, field, "must be at most " + max + " characters");
        }
        return value.isEmpty() ? null : value;
    }

    private static Location location(JsonNode node, Location current, Map<String, List<String>> errors) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            error(errors, "location", "must be an object or null");
            return null;
        }
        for (String field : node.propertyNames()) {
            if (!LOCATION_FIELDS.contains(field)) {
                error(errors, "location." + field, "is not a location field");
            }
        }
        Double latitude = coordinate(node, "latitude", current == null ? null : current.latitude(), 90, errors);
        Double longitude = coordinate(node, "longitude", current == null ? null : current.longitude(), 180, errors);
        String label = node.has("label") ? text(node.get("label"), "location.label", LABEL_MAX, errors)
                : current == null ? null : current.label();
        if (latitude == null || longitude == null) {
            return null;
        }
        return new Location(latitude, longitude, label);
    }

    private static Double coordinate(JsonNode location, String field, Double current, double bound,
                                     Map<String, List<String>> errors) {
        String path = "location." + field;
        if (!location.has(field)) {
            if (current == null) {
                error(errors, path, "is required");
            }
            return current;
        }
        JsonNode node = location.get(field);
        if (!node.isNumber()) {
            error(errors, path, "must be a number");
            return null;
        }
        double value = node.asDouble();
        if (value < -bound || value > bound || Double.isNaN(value)) {
            error(errors, path, "must be between " + (int) -bound + " and " + (int) bound);
            return null;
        }
        return value;
    }

    private static void error(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, f -> new ArrayList<>()).add(message);
    }
}
