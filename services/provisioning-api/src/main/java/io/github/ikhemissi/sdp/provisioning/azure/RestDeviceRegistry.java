package io.github.ikhemissi.sdp.provisioning.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpMethod;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** IoT Hub registry REST API with Microsoft Entra ID (role IoT Hub Registry Contributor); updates are conditional. */
public class RestDeviceRegistry implements DeviceRegistry {

    static final String API_VERSION = "2021-04-12";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AzureRest rest;

    public RestDeviceRegistry(String iotHubHost, TokenCredential credential) {
        this.rest = new AzureRest("https://" + iotHubHost, credential, "https://iothubs.azure.net/.default");
    }

    @Override
    public Outcome setEnabled(String deviceId, boolean enabled, String reason) {
        String path = "/devices/" + URLEncoder.encode(deviceId, StandardCharsets.UTF_8) + "?api-version=" + API_VERSION;
        AzureRest.Response current = rest.send(HttpMethod.GET, path, null, Map.of());
        if (current.status() == 404) {
            return Outcome.NOT_FOUND;
        }
        if (current.status() != 200) {
            throw AzureRest.failure("Reading device " + deviceId, current);
        }
        ObjectNode device = (ObjectNode) JSON.readTree(current.body());
        String wanted = enabled ? "enabled" : "disabled";
        if (wanted.equals(device.path("status").asString())) {
            return Outcome.UNCHANGED;
        }
        device.put("status", wanted);
        device.put("statusReason", reason);
        String etag = device.path("etag").asString(null);
        AzureRest.Response updated = rest.send(HttpMethod.PUT, path, JSON.writeValueAsString(device),
                etag == null ? Map.of() : Map.of(AzureRest.IF_MATCH, "\"" + etag + "\""));
        if (updated.status() != 200) {
            throw AzureRest.failure((enabled ? "Enabling" : "Disabling") + " device " + deviceId, updated);
        }
        return Outcome.CHANGED;
    }
}
