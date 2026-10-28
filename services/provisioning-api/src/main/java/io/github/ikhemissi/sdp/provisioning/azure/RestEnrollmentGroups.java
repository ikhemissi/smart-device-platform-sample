package io.github.ikhemissi.sdp.provisioning.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpMethod;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** DPS service REST API with Microsoft Entra ID (role Device Provisioning Service Data Contributor). */
public class RestEnrollmentGroups implements EnrollmentGroups {

    static final String API_VERSION = "2021-10-01";
    private static final HttpHeaderName CONTINUATION = HttpHeaderName.fromString("x-ms-continuation");
    private static final HttpHeaderName MAX_ITEMS = HttpHeaderName.fromString("x-ms-max-item-count");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AzureRest rest;

    public RestEnrollmentGroups(String dpsHost, TokenCredential credential) {
        this.rest = new AzureRest("https://" + dpsHost, credential, "https://azure-devices-provisioning.net/.default");
    }

    @Override
    public Optional<EnrollmentGroup> get(String groupId) {
        AzureRest.Response response = rest.send(HttpMethod.GET, path(groupId), null, Map.of());
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (response.status() != 200) {
            throw AzureRest.failure("Reading enrollment group " + groupId, response);
        }
        return Optional.of(EnrollmentGroup.parse(response.body(), response.etag()));
    }

    @Override
    public EnrollmentGroup put(EnrollmentGroup group) {
        Map<HttpHeaderName, String> headers = group.etag() == null ? Map.of() : Map.of(AzureRest.IF_MATCH, group.etag());
        AzureRest.Response response = rest.send(HttpMethod.PUT, path(group.id()), group.toJson(), headers);
        if (response.status() != 200 && response.status() != 201) {
            throw AzureRest.failure("Updating enrollment group " + group.id(), response);
        }
        return EnrollmentGroup.parse(response.body(), response.etag());
    }

    @Override
    public void delete(EnrollmentGroup group) {
        Map<HttpHeaderName, String> headers = group.etag() == null ? Map.of() : Map.of(AzureRest.IF_MATCH, group.etag());
        AzureRest.Response response = rest.send(HttpMethod.DELETE, path(group.id()), null, headers);
        if (response.status() != 204 && response.status() != 200 && response.status() != 404) {
            throw AzureRest.failure("Deleting enrollment group " + group.id(), response);
        }
    }

    @Override
    public List<EnrollmentGroup> listSiteGroups() {
        List<EnrollmentGroup> groups = new ArrayList<>();
        String continuation = null;
        do {
            Map<HttpHeaderName, String> headers = new HashMap<>(Map.of(MAX_ITEMS, "100"));
            if (continuation != null) {
                headers.put(CONTINUATION, continuation);
            }
            AzureRest.Response response = rest.send(HttpMethod.POST, "/enrollmentGroups/query?api-version=" + API_VERSION,
                    "{\"query\":\"*\"}", headers);
            if (response.status() != 200) {
                throw AzureRest.failure("Listing enrollment groups", response);
            }
            for (JsonNode node : JSON.readTree(response.body())) {
                EnrollmentGroup group = EnrollmentGroup.parse(JSON.writeValueAsString(node), null);
                if (group.id().startsWith(EnrollmentGroup.PREFIX)) {
                    groups.add(group);
                }
            }
            continuation = response.continuation();
        } while (continuation != null && !continuation.isBlank());
        return groups;
    }

    private static String path(String groupId) {
        return "/enrollmentGroups/" + URLEncoder.encode(groupId, StandardCharsets.UTF_8) + "?api-version=" + API_VERSION;
    }
}
