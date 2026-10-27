package io.github.ikhemissi.sdp.testsupport;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * Azure Event Hubs emulator backed by Azurite, for integration tests. The emulator joins Azurite's network
 * namespace and reaches it on localhost, so no container network is required (works on Docker and on rootless
 * Podman without CNI plugins). Emulator and Azurite use their documented, public development credentials.
 */
public final class EventHubsEmulator implements AutoCloseable {

    public static final String EVENT_HUB = "device-telemetry";
    public static final String CONSUMER_GROUP = "telemetry-decoder";

    private static final String AZURITE_IMAGE = "mcr.microsoft.com/azure-storage/azurite:3.37.0";
    private static final String EMULATOR_IMAGE = "mcr.microsoft.com/azure-messaging/eventhubs-emulator:2.2.1";
    private static final int AMQP_PORT = 5672;
    private static final int BLOB_PORT = 10000;
    /** Well-known Azurite development account key (public, documented by Microsoft; not a secret). */
    private static final String AZURITE_ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private final GenericContainer<?> azurite;
    private final GenericContainer<?> emulator;

    private EventHubsEmulator(GenericContainer<?> azurite, GenericContainer<?> emulator) {
        this.azurite = azurite;
        this.emulator = emulator;
    }

    @SuppressWarnings("resource")
    public static EventHubsEmulator start(int partitions) {
        GenericContainer<?> azurite = ContainerEngine.adapt(new GenericContainer<>(AZURITE_IMAGE)
                .withCommand("azurite", "--blobHost", "0.0.0.0", "--queueHost", "0.0.0.0", "--tableHost", "0.0.0.0",
                        "--skipApiVersionCheck", "--loose")
                // The emulator's AMQP port is published by Azurite, whose network namespace it shares.
                .withExposedPorts(BLOB_PORT, 10001, 10002, AMQP_PORT)
                .waitingFor(Wait.forLogMessage(".*Azurite Table service is successfully listening.*", 1)));
        azurite.start();

        String config = """
                {"UserConfig": {
                  "NamespaceConfig": [{"Type": "EventHub", "Name": "emulatorNs1", "Entities": [
                    {"Name": "%s", "PartitionCount": "%d", "ConsumerGroups": [{"Name": "%s"}]}]}],
                  "LoggingConfig": {"Type": "File"}}}
                """.formatted(EVENT_HUB, partitions, CONSUMER_GROUP);
        GenericContainer<?> emulator = new GenericContainer<>(EMULATOR_IMAGE)
                .withNetworkMode("container:" + azurite.getContainerId())
                .withEnv("ACCEPT_EULA", "Y")
                .withEnv("BLOB_SERVER", "localhost")
                .withEnv("METADATA_SERVER", "localhost")
                .withCopyToContainer(Transferable.of(config), "/Eventhubs_Emulator/ConfigFiles/Config.json")
                .waitingFor(Wait.forLogMessage(".*Emulator Service is Successfully Up.*", 1).withStartupTimeout(Duration.ofMinutes(3)));
        try {
            emulator.start();
        } catch (RuntimeException e) {
            azurite.stop();
            throw e;
        }
        EventHubsEmulator started = new EventHubsEmulator(azurite, emulator);
        Runtime.getRuntime().addShutdownHook(new Thread(started::close, "eventhubs-emulator-stop"));
        return started;
    }

    /** Connection string for the Event Hubs SDK (emulator only). */
    public String eventHubsConnectionString() {
        return "Endpoint=sb://" + azurite.getHost() + ":" + azurite.getMappedPort(AMQP_PORT)
                + ";SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";
    }

    /** Connection string for the Blob SDK (Azurite only). */
    public String blobConnectionString() {
        return "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=" + AZURITE_ACCOUNT_KEY
                + ";BlobEndpoint=http://" + azurite.getHost() + ":" + azurite.getMappedPort(BLOB_PORT) + "/devstoreaccount1;";
    }

    @Override
    public synchronized void close() {
        if (emulator.isRunning()) {
            emulator.stop();
        }
        if (azurite.isRunning()) {
            azurite.stop();
        }
    }
}
