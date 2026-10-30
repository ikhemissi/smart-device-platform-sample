package io.github.ikhemissi.sdp.simulator;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Simulator configuration (spec device-simulation: "Configurable fleet").
 *
 * @param formats        format mix assigned round-robin; repeat a format id to weight it
 * @param duration       optional run duration; runs until stopped when absent
 * @param corruptionRate share of messages sent with a corrupted payload (0..1)
 * @param anomalies      e.g. {@code paris-01-ruuvi5-001:temperature=12:5m[:<delay>]}
 * @param agingDevices   ids of the devices whose battery drains to its cut-off in about two weeks
 * @param transport      {@code iothub} (DPS + IoT Hub) or {@code console} (dry run)
 */
@ConfigurationProperties("sdp.simulator")
public record SimulatorProperties(
        @DefaultValue({"paris-01", "lyon-01", "marseille-01"}) List<String> sites,
        @DefaultValue("10") int devicesPerSite,
        @DefaultValue({"ruuvi-df5", "ruuvi-df3", "bthome-v2", "cayenne-lpp"}) List<String> formats,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("5s") Duration jitter,
        Duration duration,
        @DefaultValue("0") double corruptionRate,
        @DefaultValue List<String> anomalies,
        @DefaultValue List<String> agingDevices,
        @DefaultValue("iothub") String transport,
        @DefaultValue Dps dps,
        @DefaultValue Ca ca) {

    public record Dps(@DefaultValue("global.azure-devices-provisioning.net") String globalEndpoint, String idScope) {
    }

    /**
     * @param keyVaultUri Key Vault holding {@code site-<siteId>-ca} PEM bundles (Entra authentication)
     * @param directory   alternative for offline runs: directory with {@code site-<siteId>-ca.pem} files
     */
    public record Ca(String keyVaultUri, Path directory) {
    }

    public SimulatorProperties {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("sdp.simulator.interval must be positive");
        }
        if (jitter.isNegative() || jitter.compareTo(interval) >= 0) {
            throw new IllegalArgumentException("sdp.simulator.jitter must be >= 0 and smaller than the interval");
        }
        anomalies = anomalies == null ? List.of() : anomalies.stream().filter(a -> !a.isBlank()).toList();
        agingDevices = agingDevices == null ? List.of() : agingDevices.stream().map(String::trim).filter(a -> !a.isBlank()).toList();
    }
}
