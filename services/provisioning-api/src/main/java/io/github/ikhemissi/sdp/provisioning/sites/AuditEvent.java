package io.github.ikhemissi.sdp.provisioning.sites;

import java.time.Instant;
import java.util.Map;

/** A recorded change to a site's trust. */
public record AuditEvent(long id, String siteId, Instant occurredAt, String actorId, String actorName, String action,
                         String fingerprint, Map<String, Object> details) {

    public static final String SITE_CREATED = "SITE_CREATED";
    public static final String CERTIFICATE_ADDED = "CERTIFICATE_ADDED";
    public static final String CERTIFICATE_REMOVED = "CERTIFICATE_REMOVED";
    public static final String DEVICES_DISABLED = "DEVICES_DISABLED";
    public static final String DEVICES_ENABLED = "DEVICES_ENABLED";
    public static final String RECONCILED_ADDED = "RECONCILED_ADDED";
    public static final String RECONCILED_REMOVED = "RECONCILED_REMOVED";
}
