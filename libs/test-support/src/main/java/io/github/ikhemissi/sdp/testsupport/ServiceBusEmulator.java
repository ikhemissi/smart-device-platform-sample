package io.github.ikhemissi.sdp.testsupport;

import java.time.Duration;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * Azure Service Bus emulator backed by Azure SQL Edge, for integration tests: the topic {@value #TOPIC} with
 * duplicate detection and the subscription {@value #SUBSCRIPTION} selecting messages with a SQL filter, as in Azure
 * (implementation guidelines §9), except the duplicate detection window: 5 minutes, the emulator's maximum (10 in
 * Azure). The emulator joins SQL Edge's network namespace and reaches it on localhost (works on rootless Podman
 * without CNI plugins). The SQL password is random per run; the connection string uses the emulator's documented
 * development key.
 */
public final class ServiceBusEmulator implements AutoCloseable {

    public static final String TOPIC = "measurements";
    public static final String SUBSCRIPTION = "alerting";

    private static final String SQL_IMAGE = "mcr.microsoft.com/azure-sql-edge:2.0.0";
    private static final String EMULATOR_IMAGE = "mcr.microsoft.com/azure-messaging/servicebus-emulator:2.0.1";
    private static final int AMQP_PORT = 5672;

    private final GenericContainer<?> sql;
    private final GenericContainer<?> emulator;

    private ServiceBusEmulator(GenericContainer<?> sql, GenericContainer<?> emulator) {
        this.sql = sql;
        this.emulator = emulator;
    }

    /** Starts the emulator; {@code filter} is the subscription's SQL filter, e.g. {@code siteId = 'paris-01'}. */
    @SuppressWarnings("resource")
    public static ServiceBusEmulator start(String filter) {
        String password = "Sb-" + UUID.randomUUID() + "-1a";
        GenericContainer<?> sql = ContainerEngine.adapt(new GenericContainer<>(SQL_IMAGE)
                .withEnv("ACCEPT_EULA", "Y")
                .withEnv("MSSQL_SA_PASSWORD", password)
                // The emulator's AMQP port is published by SQL Edge, whose network namespace it shares.
                .withExposedPorts(1433, AMQP_PORT)
                .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client connections.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3))));
        sql.start();

        String config = """
                {"UserConfig": {
                  "Namespaces": [{"Name": "sbemulatorns", "Queues": [], "Topics": [{
                    "Name": "%s",
                    "Properties": {"DefaultMessageTimeToLive": "PT1H", "DuplicateDetectionHistoryTimeWindow": "PT5M",
                                   "RequiresDuplicateDetection": true},
                    "Subscriptions": [{
                      "Name": "%s",
                      "Properties": {"DeadLetteringOnMessageExpiration": true, "DefaultMessageTimeToLive": "PT1H",
                                     "LockDuration": "PT1M", "MaxDeliveryCount": 10, "ForwardDeadLetteredMessagesTo": "",
                                     "ForwardTo": "", "RequiresSession": false},
                      "Rules": [{"Name": "monitored-devices",
                                 "Properties": {"FilterType": "Sql", "SqlFilter": {"SqlExpression": "%s"}}}]}]}]}],
                  "Logging": {"Type": "File"}}}
                """.formatted(TOPIC, SUBSCRIPTION, filter.replace("\"", "\\\""));
        GenericContainer<?> emulator = new GenericContainer<>(EMULATOR_IMAGE)
                .withNetworkMode("container:" + sql.getContainerId())
                .withEnv("ACCEPT_EULA", "Y")
                .withEnv("SQL_SERVER", "localhost")
                .withEnv("MSSQL_SA_PASSWORD", password)
                .withCopyToContainer(Transferable.of(config), "/ServiceBus_Emulator/ConfigFiles/Config.json")
                .waitingFor(Wait.forLogMessage(".*Emulator Service is Successfully Up.*", 1).withStartupTimeout(Duration.ofMinutes(4)));
        try {
            emulator.start();
        } catch (RuntimeException e) {
            sql.stop();
            throw e;
        }
        ServiceBusEmulator started = new ServiceBusEmulator(sql, emulator);
        Runtime.getRuntime().addShutdownHook(new Thread(started::close, "servicebus-emulator-stop"));
        return started;
    }

    /** Connection string for the Service Bus SDK (emulator only). */
    public String connectionString() {
        return "Endpoint=sb://" + sql.getHost() + ":" + sql.getMappedPort(AMQP_PORT)
                + ";SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";
    }

    @Override
    public synchronized void close() {
        if (emulator.isRunning()) {
            emulator.stop();
        }
        if (sql.isRunning()) {
            sql.stop();
        }
    }
}
