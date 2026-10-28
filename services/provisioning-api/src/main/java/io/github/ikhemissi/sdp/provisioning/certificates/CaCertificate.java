package io.github.ikhemissi.sdp.provisioning.certificates;

import java.time.Instant;

/**
 * A validated CA certificate: what the platform stores about it, and its PEM form (sent to DPS only).
 *
 * @param fingerprint SHA-256 of the DER encoding, lowercase hex
 */
public record CaCertificate(String subject, String issuer, String fingerprint, Instant notBefore, Instant notAfter, String pem) {

    /** Name of the DPS certificate registered for a site: {@code site-<siteId>-<first 12 hex of the fingerprint>}. */
    public String dpsName(String siteId) {
        return "site-" + siteId + "-" + fingerprint.substring(0, 12);
    }
}
