package io.github.ikhemissi.sdp.deviceapi.anomalies;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** An Editor's review of an anomaly: is the automated classification correct? */
@Schema(name = "AnomalyReviewInput")
public record ReviewInput(
        @NotNull @Pattern(regexp = "correct|incorrect", message = "must be correct or incorrect")
        @Schema(allowableValues = {"correct", "incorrect"}) String verdict,
        @Size(max = 1000, message = "must be at most 1000 characters")
        @Schema(nullable = true, example = "Door left open during a delivery") String comment) {

    String normalizedComment() {
        return comment == null || comment.isBlank() ? null : comment.strip();
    }
}
