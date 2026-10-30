package io.github.ikhemissi.sdp.deviceapi.maintenance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** An Editor's status change of a queue item. */
@Schema(name = "MaintenanceStatusInput")
public record StatusInput(
        @NotNull @Pattern(regexp = "planned|done", message = "must be planned or done")
        @Schema(allowableValues = {"planned", "done"}) String status,
        @Size(max = 1000, message = "must be at most 1000 characters")
        @Schema(nullable = true, example = "Battery replaced") String comment) {

    String normalizedComment() {
        return comment == null || comment.isBlank() ? null : comment.strip();
    }
}
