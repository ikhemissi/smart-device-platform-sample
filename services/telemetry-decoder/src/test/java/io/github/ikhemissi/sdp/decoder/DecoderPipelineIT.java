package io.github.ikhemissi.sdp.decoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.core.amqp.models.AmqpMessageId;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.SendOptions;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.ListBlobsOptions;
import io.github.ikhemissi.sdp.testsupport.EventHubsEmulator;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tasks 4.1 and 4.4 — the full decoder against the Event Hubs emulator, Azurite (checkpoints) and PostgreSQL.
 * Spec telemetry-ingestion: "Batch persisted then acknowledged" and "Storage unavailable".
 */
@SpringBootTest
class DecoderPipelineIT {

    private static final String RUUVI_DF5 = "0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F";
    private static final String CHECKPOINTS = "checkpoints";
    private static EventHubsEmulator eventHubs;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        eventHubs = EventHubsEmulator.start(2);
        DecoderDatabase.registerDecoderDataSource(registry);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("sdp.decoder.eventhubs.connection-string", eventHubs::eventHubsConnectionString);
        registry.add("sdp.decoder.checkpoint.connection-string", eventHubs::blobConnectionString);
        registry.add("sdp.decoder.checkpoint.container-name", () -> CHECKPOINTS);
        registry.add("sdp.decoder.batch.max-size", () -> "50");
        registry.add("sdp.decoder.batch.max-wait", () -> "1s");
        registry.add("sdp.decoder.retry.initial-backoff", () -> "200ms");
        registry.add("sdp.decoder.retry.max-backoff", () -> "1s");
    }

    @AfterAll
    static void stopEmulator() {
        if (eventHubs != null) {
            eventHubs.close();
        }
    }

    @Autowired
    IngestionHealthIndicator health;

    private final JdbcTemplate admin = DecoderDatabase.admin();
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void messagesArePersistedAndCheckpointedAfterPersistence() {
        send(40, "ok");

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(measurements("ok")).isEqualTo(40));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(checkpointedSequenceNumbers().values()).hasSize(2));
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.devices WHERE device_id LIKE ?", Integer.class,
                "it-" + run + "-ok-%")).isEqualTo(4);
        assertThat(admin.queryForObject("SELECT count(DISTINCT device_id) FROM sdp.device_metrics WHERE device_id LIKE ? AND metric = 'temperature'",
                Integer.class, "it-" + run + "-ok-%")).as("metrics reported after one batch").isEqualTo(4);
    }

    @Test
    void ingestionWatermarksAdvanceOnEveryPartition() {
        send(4, "watermark");
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(measurements("watermark")).isEqualTo(4));
        java.sql.Timestamp sent = admin.queryForObject(
                "SELECT max(measured_at) FROM sdp.measurements WHERE device_id LIKE ?", java.sql.Timestamp.class, "it-" + run + "-watermark-%");

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(admin.queryForList(
                "SELECT partition_id FROM sdp.ingestion_watermarks WHERE watermark > ?", String.class, sent))
                .as("caught-up partitions move past the last persisted message").containsExactlyInAnyOrder("0", "1"));
    }

    @Test
    void databaseOutageDelaysButNeverLosesMessages() throws Exception {
        send(4, "warmup");
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(measurements("warmup")).isEqualTo(4));
        await().atMost(Duration.ofSeconds(30)).until(() -> checkpointedSequenceNumbers().size() == 2);
        Map<String, Long> before = waitForStableCheckpoints();

        makeDatabaseUnavailable();
        try {
            send(20, "outage");
            await().atMost(Duration.ofSeconds(60)).until(() -> health.health().getStatus().equals(Status.DOWN));
            Thread.sleep(3_000);
            assertThat(checkpointedSequenceNumbers()).as("no checkpoint while unpersisted").isEqualTo(before);
            assertThat(measurements("outage")).isZero();
        } finally {
            restoreDatabase();
        }

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(measurements("outage")).isEqualTo(20));
        await().atMost(Duration.ofSeconds(30)).until(() -> health.health().getStatus().equals(Status.UP));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(checkpointedSequenceNumbers()).allSatisfy((partition, seq) -> assertThat(seq).isGreaterThan(before.getOrDefault(partition, -1L))));
        assertThat(admin.queryForObject("""
                SELECT count(*) - count(DISTINCT (device_id, message_key)) FROM sdp.measurements WHERE device_id LIKE ?
                """, Integer.class, "it-" + run + "-outage-%")).as("no duplicates").isZero();
    }

    /** 4 devices, each pinned to one of the 2 partitions (IoT Hub also keeps a device on one partition). */
    private void send(int count, String scenario) {
        try (EventHubProducerClient producer = new EventHubClientBuilder()
                .connectionString(eventHubs.eventHubsConnectionString(), EventHubsEmulator.EVENT_HUB)
                .buildProducerClient()) {
            for (int i = 0; i < count; i++) {
                String deviceId = "it-" + run + "-" + scenario + "-" + (i % 4);
                EventData event = new EventData(HexFormat.of().parseHex(RUUVI_DF5));
                event.getRawAmqpMessage().getMessageAnnotations().put("iothub-connection-device-id", deviceId);
                event.getRawAmqpMessage().getMessageAnnotations().put("iothub-enqueuedtime", Date.from(Instant.now()));
                event.getRawAmqpMessage().getProperties().setMessageId(new AmqpMessageId(scenario + "-" + i));
                event.getProperties().put("siteId", "paris-01");
                producer.send(List.of(event), new SendOptions().setPartitionId(String.valueOf(i % 4 % 2)));
            }
        }
    }

    private int measurements(String scenario) {
        return admin.queryForObject("SELECT count(*) FROM sdp.measurements WHERE device_id LIKE ?", Integer.class,
                "it-" + run + "-" + scenario + "-%");
    }

    private Map<String, Long> checkpointedSequenceNumbers() {
        BlobContainerClient container = new BlobContainerClientBuilder()
                .connectionString(eventHubs.blobConnectionString()).containerName(CHECKPOINTS).buildClient();
        Map<String, Long> result = new HashMap<>();
        if (!container.exists()) {
            return result;
        }
        container.listBlobs(new ListBlobsOptions().setDetails(new BlobListDetails().setRetrieveMetadata(true)), null)
                .stream()
                .filter(b -> b.getName().contains("/checkpoint/") && b.getMetadata() != null && b.getMetadata().get("sequencenumber") != null)
                .forEach(b -> result.put(b.getName().substring(b.getName().lastIndexOf('/') + 1),
                        Long.parseLong(b.getMetadata().get("sequencenumber"))));
        return result;
    }

    private Map<String, Long> waitForStableCheckpoints() throws InterruptedException {
        Map<String, Long> previous = checkpointedSequenceNumbers();
        while (true) {
            Thread.sleep(2_000);
            Map<String, Long> current = checkpointedSequenceNumbers();
            if (current.equals(previous)) {
                return current;
            }
            previous = current;
        }
    }

    /** Simulates the database becoming unavailable for the decoder: logins refused, open sessions killed. */
    private void makeDatabaseUnavailable() {
        admin.execute("ALTER ROLE \"" + DecoderDatabase.DECODER_PRINCIPAL + "\" NOLOGIN");
        admin.queryForList("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = ?",
                DecoderDatabase.DECODER_PRINCIPAL);
    }

    private void restoreDatabase() {
        admin.execute("ALTER ROLE \"" + DecoderDatabase.DECODER_PRINCIPAL + "\" LOGIN");
    }
}
