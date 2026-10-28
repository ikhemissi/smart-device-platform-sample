package io.github.ikhemissi.sdp.provisioning;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param dpsResourceId  Azure resource id of the Device Provisioning Service (CA certificates, control plane)
 * @param dpsHost        DPS service host, e.g. {@code provs-x.azure-devices-provisioning.net} (enrollment groups)
 * @param iotHubHost     IoT Hub host name (device registry)
 * @param reconciliation alignment of platform records with DPS (start-up and periodic)
 * @param syncDeviceLimit device operations on sites with at most this many devices complete within the request
 */
@ConfigurationProperties("sdp.provisioning")
public record ProvisioningProperties(
        String dpsResourceId,
        String dpsHost,
        String iotHubHost,
        @DefaultValue Reconciliation reconciliation,
        @DefaultValue("100") int syncDeviceLimit) {

    public record Reconciliation(@DefaultValue("true") boolean enabled, @DefaultValue("1h") Duration interval) {
    }
}
