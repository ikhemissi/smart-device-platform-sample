package io.github.ikhemissi.sdp.provisioning.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DPS certificates through Azure Resource Manager with Microsoft Entra ID (custom role limited to DPS certificates).
 * Uploading as verified is what DPS requires before devices chaining to the CA can provision.
 */
public class ArmDpsCertificates implements DpsCertificates {

    static final String API_VERSION = "2022-02-05";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AzureRest rest;
    private final String dpsResourceId;

    public ArmDpsCertificates(String dpsResourceId, TokenCredential credential) {
        this.rest = new AzureRest("https://management.azure.com", credential, "https://management.azure.com/.default");
        this.dpsResourceId = dpsResourceId;
    }

    @Override
    public void register(String name, String pem) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("properties")
                .put("certificate", Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII)))
                .put("isVerified", true);
        AzureRest.Response response = rest.send(HttpMethod.PUT, path(name), JSON.writeValueAsString(body), Map.of());
        if (response.status() != 200 && response.status() != 201) {
            throw AzureRest.failure("Registering DPS certificate " + name, response);
        }
    }

    @Override
    public Optional<RegisteredCertificate> find(String name) {
        AzureRest.Response response = rest.send(HttpMethod.GET, path(name), null, Map.of());
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (response.status() != 200) {
            throw AzureRest.failure("Reading DPS certificate " + name, response);
        }
        JsonNode node = JSON.readTree(response.body());
        String encoded = node.path("properties").path("certificate").asString("");
        return Optional.of(new RegisteredCertificate(name, toPem(encoded), node.path("etag").asString(null)));
    }

    @Override
    public void delete(String name) {
        Optional<RegisteredCertificate> existing = find(name);
        if (existing.isEmpty()) {
            return;
        }
        AzureRest.Response response = rest.send(HttpMethod.DELETE, path(name), null,
                existing.get().etag() == null ? Map.of() : Map.of(AzureRest.IF_MATCH, existing.get().etag()));
        if (response.status() != 200 && response.status() != 204 && response.status() != 404) {
            throw AzureRest.failure("Deleting DPS certificate " + name, response);
        }
    }

    /** DPS returns the uploaded content base64-encoded (a PEM file, or DER). */
    static String toPem(String encoded) {
        byte[] bytes = Base64.getMimeDecoder().decode(encoded);
        String text = new String(bytes, StandardCharsets.US_ASCII);
        if (text.contains("-----BEGIN CERTIFICATE-----")) {
            return text;
        }
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(bytes) + "\n-----END CERTIFICATE-----\n";
    }

    private String path(String name) {
        return dpsResourceId + "/certificates/" + name + "?api-version=" + API_VERSION;
    }
}
