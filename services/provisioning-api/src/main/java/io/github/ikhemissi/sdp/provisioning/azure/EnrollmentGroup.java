package io.github.ikhemissi.sdp.provisioning.azure;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A DPS enrollment group for one site (ADR-0006): X.509 attestation referencing up to two verified CA certificates
 * ({@code primary}, {@code secondary}), device twin tag {@code siteId}. Unknown fields of the DPS document are kept.
 */
public final class EnrollmentGroup {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String PREFIX = "site-";

    private final ObjectNode document;
    private final String etag;

    private EnrollmentGroup(ObjectNode document, String etag) {
        this.document = document;
        this.etag = etag;
    }

    public static String idOf(String siteId) {
        return PREFIX + siteId;
    }

    /** New group for a site, enabled, with devices assigned to the linked IoT hub and moved with their data. */
    public static EnrollmentGroup create(String siteId) {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("enrollmentGroupId", idOf(siteId));
        doc.putObject("attestation").put("type", "x509").putObject("x509").putObject("caReferences");
        doc.putObject("initialTwin").putObject("tags").put("siteId", siteId);
        doc.withObject("initialTwin").putObject("properties").putObject("desired");
        doc.put("provisioningStatus", "enabled");
        doc.putObject("reprovisionPolicy").put("updateHubAssignment", true).put("migrateDeviceData", true);
        return new EnrollmentGroup(doc, null);
    }

    public static EnrollmentGroup parse(String json, String etag) {
        ObjectNode doc = (ObjectNode) JSON.readTree(json);
        String bodyEtag = doc.path("etag").asString(null);
        return new EnrollmentGroup(doc, etag != null ? etag : bodyEtag);
    }

    public String id() {
        return document.path("enrollmentGroupId").asString();
    }

    /** Site id from the twin tag, or from the group id. */
    public String siteId() {
        String tag = document.path("initialTwin").path("tags").path("siteId").asString(null);
        return tag != null ? tag : id().substring(PREFIX.length());
    }

    /** {@code null} for a new group. */
    public String etag() {
        return etag;
    }

    public String primary() {
        return caReferences().path("primary").asString(null);
    }

    public String secondary() {
        return caReferences().path("secondary").asString(null);
    }

    public boolean enabled() {
        return !"disabled".equals(document.path("provisioningStatus").asString("enabled"));
    }

    /** Copy with the given CA references ({@code null} clears a slot) and provisioning enabled. */
    public EnrollmentGroup withReferences(String primary, String secondary) {
        ObjectNode copy = document.deepCopy();
        copy.remove("etag");
        ObjectNode refs = copy.withObject("attestation").put("type", "x509").withObject("x509").putObject("caReferences");
        if (primary != null) {
            refs.put("primary", primary);
        }
        if (secondary != null) {
            refs.put("secondary", secondary);
        }
        copy.put("provisioningStatus", "enabled");
        return new EnrollmentGroup(copy, etag);
    }

    /** Copy that no device can provision through; references are kept (DPS requires one). */
    public EnrollmentGroup disabled() {
        ObjectNode copy = document.deepCopy();
        copy.remove("etag");
        copy.put("provisioningStatus", "disabled");
        return new EnrollmentGroup(copy, etag);
    }

    public String toJson() {
        ObjectNode copy = document.deepCopy();
        copy.remove("etag");
        return JSON.writeValueAsString(copy);
    }

    private tools.jackson.databind.JsonNode caReferences() {
        return document.path("attestation").path("x509").path("caReferences");
    }
}
