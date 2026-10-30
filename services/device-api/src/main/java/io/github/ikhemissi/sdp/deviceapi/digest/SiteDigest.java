package io.github.ikhemissi.sdp.deviceapi.digest;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A site's digest status: whether the site gets digests, its latest digest and the pending regeneration request.
 */
@Schema(name = "SiteDigest")
public record SiteDigest(
        String siteId,
        @Schema(description = "Whether the site gets digests in this environment") boolean enabled,
        @Schema(nullable = true, description = "The latest digest; null when there is none yet") Digest digest,
        @Schema(nullable = true, description = "A regeneration request not served yet") PendingRequest pendingRequest) {

    /** One digest: the figures computed by the platform and the brief written from them. */
    @Schema(name = "Digest")
    public record Digest(
            long id,
            @Schema(allowableValues = {"scheduled", "requested"}) String trigger,
            Instant periodFrom,
            Instant periodTo,
            @Schema(description = "Start of the comparison period (it ends at periodFrom)") Instant comparisonFrom,
            Instant generatedAt,
            @Schema(nullable = true, description = "Null when the language model failed; a later run adds it") String brief,
            @Schema(nullable = true) String briefModel,
            @Schema(nullable = true) String requestedBy,
            @JsonRawValue
            @Schema(type = "object", description = "Devices, metrics, alerts, anomalies, rejected messages and certificates "
                    + "of the period, as computed by the platform")
            String figures) {
    }

    /** A regeneration requested by an Editor, served by the digest job within minutes. */
    @Schema(name = "DigestRequest")
    public record PendingRequest(Instant requestedAt, String requestedBy) {
    }
}
