package io.github.ikhemissi.sdp.simulator;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import io.github.ikhemissi.sdp.codecs.PayloadFormat;
import io.github.ikhemissi.sdp.simulator.certs.DeviceCertificateFactory;
import io.github.ikhemissi.sdp.simulator.certs.SiteCaProvider;
import io.github.ikhemissi.sdp.simulator.fleet.Anomaly;
import io.github.ikhemissi.sdp.simulator.fleet.PayloadFactory;
import io.github.ikhemissi.sdp.simulator.fleet.SimulatedDevice;
import io.github.ikhemissi.sdp.simulator.transport.ConsoleTransport;
import io.github.ikhemissi.sdp.simulator.transport.DeviceTransport;
import io.github.ikhemissi.sdp.simulator.transport.IotHubTransport;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class DeviceSimulatorApplication {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulatorApplication.class);

    public static void main(String[] args) throws InterruptedException {
        ConfigurableApplicationContext context = SpringApplication.run(DeviceSimulatorApplication.class, args);
        // Device loops run on (daemon) virtual threads: keep the JVM alive until the simulation stops.
        context.getBean(FleetSimulator.class).awaitTermination();
        // Exit explicitly: the IoT provisioning SDK can leave non-daemon executor threads behind (e.g. after a
        // refused registration), which would otherwise keep the JVM running after the simulation ended.
        System.exit(SpringApplication.exit(context));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FleetSimulator fleetSimulator(SimulatorProperties properties, DeviceTransport transport, Clock clock, OpenTelemetry openTelemetry) {
        List<PayloadFormat> formats = properties.formats().stream().map(DeviceSimulatorApplication::format).toList();
        List<SimulatedDevice> fleet = SimulatedDevice.plan(properties.sites(), properties.devicesPerSite(), formats);
        List<Anomaly> anomalies = properties.anomalies().stream().map(Anomaly::parse).toList();
        anomalies.stream()
                .filter(a -> fleet.stream().noneMatch(d -> d.deviceId().equals(a.deviceId())))
                .forEach(a -> log.warn("Anomaly targets unknown device {} (ignored)", a.deviceId()));
        return new FleetSimulator(fleet, anomalies, properties, transport,
                new PayloadFactory(properties.corruptionRate(), System.nanoTime()), clock, openTelemetry);
    }

    @Bean
    DeviceTransport deviceTransport(SimulatorProperties properties, Clock clock) {
        return switch (properties.transport()) {
            case "console" -> new ConsoleTransport();
            case "iothub" -> new IotHubTransport(properties.dps().globalEndpoint(), properties.dps().idScope(),
                    siteCaProvider(properties.ca()), new DeviceCertificateFactory(), clock);
            default -> throw new IllegalArgumentException("sdp.simulator.transport must be 'iothub' or 'console'");
        };
    }

    private static SiteCaProvider siteCaProvider(SimulatorProperties.Ca ca) {
        if (ca.directory() != null) {
            return SiteCaProvider.directory(ca.directory());
        }
        if (ca.keyVaultUri() == null || ca.keyVaultUri().isBlank()) {
            throw new IllegalStateException("sdp.simulator.ca.key-vault-uri (or ca.directory) is required for the iothub transport");
        }
        // Managed identity in Azure (AZURE_CLIENT_ID), developer identity (az login) locally.
        return SiteCaProvider.keyVault(new SecretClientBuilder().vaultUrl(ca.keyVaultUri())
                .credential(new DefaultAzureCredentialBuilder().build()).buildClient());
    }

    private static PayloadFormat format(String id) {
        return Arrays.stream(PayloadFormat.values()).filter(f -> f.id().equals(id.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown format '" + id + "' in sdp.simulator.formats"));
    }
}
