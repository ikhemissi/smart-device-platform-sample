package io.github.ikhemissi.sdp.provisioning.sites;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * A certificate trusted by a site.
 *
 * @param status  {@code valid}, {@code expiring} (ends within 30 days) or {@code expired}, at request time
 * @param addedBy object id of the Editor who added it, or {@code deployment} / {@code reconciliation}
 */
public record SiteCertificate(String siteId, String fingerprint, String slot, String subject, String issuer,
                              Instant notBefore, Instant notAfter, String status, String addedBy, String addedByName,
                              Instant addedAt, @JsonIgnore String dpsCertificateName) {
}
