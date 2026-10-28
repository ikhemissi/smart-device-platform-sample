package io.github.ikhemissi.sdp.simulator.transport;

import com.microsoft.azure.sdk.iot.device.ClientOptions;
import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.Message;
import com.microsoft.azure.sdk.iot.device.transport.IotHubConnectionStatus;
import com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClient;
import com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClientRegistrationResult;
import com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClientStatus;
import com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClientTransportProtocol;
import io.github.ikhemissi.sdp.simulator.certs.DeviceCertificateFactory;
import io.github.ikhemissi.sdp.simulator.certs.DeviceCertificateFactory.DeviceCredentials;
import io.github.ikhemissi.sdp.simulator.certs.SiteCaProvider;
import io.github.ikhemissi.sdp.simulator.certs.X509DeviceSecurityProvider;
import io.github.ikhemissi.sdp.fleet.SimulatedDevice;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisions each device through DPS with an X.509 certificate issued by its site CA (enrollment group per site,
 * ADR-0006), then sends messages to the assigned IoT Hub over MQTT.
 */
public final class IotHubTransport implements DeviceTransport {

    private static final Logger log = LoggerFactory.getLogger(IotHubTransport.class);
    static final String CONTENT_TYPE = "application/octet-stream";
    static final String TRACEPARENT = "traceparent";

    private final String dpsGlobalEndpoint;
    private final String idScope;
    private final SiteCaProvider siteCas;
    private final DeviceCertificateFactory certificates;
    private final Clock clock;

    public IotHubTransport(String dpsGlobalEndpoint, String idScope, SiteCaProvider siteCas,
                           DeviceCertificateFactory certificates, Clock clock) {
        if (idScope == null || idScope.isBlank()) {
            throw new IllegalStateException("sdp.simulator.dps.id-scope is required for the iothub transport");
        }
        this.dpsGlobalEndpoint = dpsGlobalEndpoint;
        this.idScope = idScope;
        this.siteCas = siteCas;
        this.certificates = certificates;
        this.clock = clock;
    }

    @Override
    public DeviceConnection connect(SimulatedDevice device) throws Exception {
        DeviceCredentials credentials = certificates.issue(siteCas.siteCa(device.siteId()), device.deviceId(), clock.instant());
        X509DeviceSecurityProvider security = new X509DeviceSecurityProvider(credentials);

        ProvisioningDeviceClient provisioning = ProvisioningDeviceClient.create(
                dpsGlobalEndpoint, idScope, ProvisioningDeviceClientTransportProtocol.MQTT, security);
        ProvisioningDeviceClientRegistrationResult result;
        try {
            result = provisioning.registerDeviceSync();
        } finally {
            provisioning.close();
        }
        if (result.getProvisioningDeviceClientStatus() != ProvisioningDeviceClientStatus.PROVISIONING_DEVICE_STATUS_ASSIGNED) {
            throw new IllegalStateException("device " + device.deviceId() + " not assigned by DPS: "
                    + result.getProvisioningDeviceClientStatus() + " " + result.getSubstatus());
        }
        log.info("Device {} provisioned to {}", result.getDeviceId(), result.getIothubUri());

        DeviceClient client = new DeviceClient(result.getIothubUri(), result.getDeviceId(), security,
                IotHubClientProtocol.MQTT, ClientOptions.builder().keepAliveInterval(120).build());
        // DISCONNECTED (not DISCONNECTED_RETRYING) means the client gave up, e.g. while the device was disabled.
        AtomicBoolean lost = new AtomicBoolean();
        client.setConnectionStatusChangeCallback(context -> {
            if (context.getNewStatus() == IotHubConnectionStatus.DISCONNECTED) {
                lost.set(true);
            }
        }, null);
        client.open(true);
        return new DeviceConnection() {
            @Override
            public void send(OutgoingMessage message) throws Exception {
                client.sendEvent(toIotHubMessage(message));
            }

            @Override
            public boolean lost() {
                return lost.get();
            }

            @Override
            public void close() {
                client.close();
            }
        };
    }

    static Message toIotHubMessage(OutgoingMessage outgoing) {
        Message message = new Message(outgoing.payload());
        message.setMessageId(outgoing.messageId());
        message.setContentType(CONTENT_TYPE);
        if (outgoing.traceparent() != null) {
            message.setProperty(TRACEPARENT, outgoing.traceparent());
        }
        return message;
    }
}
