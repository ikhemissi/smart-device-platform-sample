package io.github.ikhemissi.sdp.provisioning.azure;

import tools.jackson.databind.JsonNode;
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
        ObjectNode document = JSON.createObjectNode();
        document.put("enrollmentGroupId", idOf(siteId));
        document.putObject("attestation").put("type", "x509").putObject("x509").putObject("caReferences");
        document.putObject("initialTwin").putObject("tags").put("siteId", siteId);
        document.withObject("initialTwin").putObject("properties").putObject("desired");
        document.put("provisioningStatus", "enabled");
        document.putObject("reprovisionPolicy").put("updateHubAssignment", true).put("migrateDeviceData", true);
        return new EnrollmentGroup(document, null);
    }

    public static EnrollmentGroup parse(String json, String etag) {
        ObjectNode document = (ObjectNode) JSON.readTree(json);
        String bodyEtag = document.path("etag").asString(null);
        return new EnrollmentGroup(document, etag != null ? etag : bodyEtag);
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
        ObjectNode copy = documentWithoutEtag();
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
        ObjectNode copy = documentWithoutEtag();
        copy.put("provisioningStatus", "disabled");
        return new EnrollmentGroup(copy, etag);
    }

    public String toJson() {
        return JSON.writeValueAsString(documentWithoutEtag());
    }

    /** The entity tag travels in the {@code If-Match} header, never in the document sent to DPS. */
    private ObjectNode documentWithoutEtag() {
        ObjectNode copy = document.deepCopy();
        copy.remove("etag");
        return copy;
    }

    private JsonNode caReferences() {
        return document.path("attestation").path("x509").path("caReferences");
    }
}
