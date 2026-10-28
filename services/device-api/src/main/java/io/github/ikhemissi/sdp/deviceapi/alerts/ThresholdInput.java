package io.github.ikhemissi.sdp.deviceapi.alerts;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Threshold sent by an Editor (creation, or full replacement with If-Match). */
@Schema(name = "ThresholdInput")
public record ThresholdInput(
        @NotBlank @Size(max = 64) @Schema(example = "temperature") String metric,
        @Schema(nullable = true, example = "2") BigDecimal min,
        @Schema(nullable = true, example = "8") BigDecimal max,
        @NotNull @Size(min = 1, max = 10, message = "must list 1 to 10 email addresses")
        @Schema(example = "[\"cold-room@example.com\"]") List<String> recipients,
        @Schema(defaultValue = "true") Boolean enabled) {

    static final int MAX_PER_DEVICE = 20;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,;<>\"]+@[^@\\s,;<>\"]+\\.[^@\\s,;<>\"]+$");

    /** Rules across fields (bean validation covers presence and sizes); empty when valid. */
    Map<String, List<String>> violations() {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        if (min == null && max == null) {
            add(errors, "min", "or max must be set");
        }
        if (min != null && max != null && min.compareTo(max) >= 0) {
            add(errors, "min", "must be lower than max");
        }
        if (recipients != null) {
            List<String> invalid = recipients.stream()
                    .filter(r -> r == null || r.length() > 254 || !EMAIL.matcher(r.strip()).matches()).map(r -> "'" + r + "'").toList();
            if (!invalid.isEmpty()) {
                add(errors, "recipients", "must be valid email addresses (" + String.join(", ", invalid) + ")");
            }
        }
        return errors;
    }

    /** Recipients trimmed, lower-cased and without duplicates. */
    List<String> normalizedRecipients() {
        return new ArrayList<>(new LinkedHashSet<>(recipients.stream().map(r -> r.strip().toLowerCase(Locale.ROOT)).toList()));
    }

    boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    private static void add(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, f -> new ArrayList<>()).add(message);
    }
}
