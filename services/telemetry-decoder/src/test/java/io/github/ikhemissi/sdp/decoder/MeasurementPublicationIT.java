package io.github.ikhemissi.sdp.decoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.core.amqp.models.AmqpMessageId;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.SendOptions;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.ListBlobsOptions;
import io.github.ikhemissi.sdp.decoder.ingest.MeasurementPublisher;
import io.github.ikhemissi.sdp.decoder.servicebus.ServiceBusMeasurementPublisher;
import io.github.ikhemissi.sdp.testsupport.EventHubsEmulator;
import io.github.ikhemissi.sdp.testsupport.ServiceBusEmulator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M5 tasks 2.1 and 2.2 — the decoder publishes every persisted measurement to the Service Bus topic before
 * checkpointing (Event Hubs, Azurite, Service Bus emulators and PostgreSQL). Spec threshold-alerting: "Measurement
 * publication for alerting".
 */
@SpringBootTest(properties = "sdp.decoder.publication.enabled=true")
class MeasurementPublicationIT {

    private static final String RUUVI_DF5 = "0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F";
    private static final String CHECKPOINTS = "checkpoints";
    private static EventHubsEmulator eventHubs;
    private static ServiceBusEmulator serviceBus;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        eventHubs = EventHubsEmulator.start(2);
        serviceBus = ServiceBusEmulator.start("siteId = 'paris-01'");
        DecoderDatabase.registerDecoderDataSource(registry);
        registry.add("sdp.decoder.eventhubs.connection-string", eventHubs::eventHubsConnectionString);
        registry.add("sdp.decoder.checkpoint.connection-string", eventHubs::blobConnectionString);
        registry.add("sdp.decoder.checkpoint.container-name", () -> CHECKPOINTS);
        registry.add("sdp.decoder.publication.connection-string", serviceBus::connectionString);
        registry.add("sdp.decoder.batch.max-size", () -> "50");
        registry.add("sdp.decoder.batch.max-wait", () -> "1s");
        registry.add("sdp.decoder.retry.initial-backoff", () -> "200ms");
        registry.add("sdp.decoder.retry.max-backoff", () -> "1s");
    }

    @AfterAll
    static void stopEmulators() {
        if (eventHubs != null) {
            eventHubs.close();
        }
        if (serviceBus != null) {
            serviceBus.close();
        }
    }

    /** The real publisher, made to fail on demand (Service Bus unavailable). */
    @TestConfiguration
    static class FailingPublisher {
        static final AtomicBoolean failing = new AtomicBoolean();

        @Bean
        @Primary
        MeasurementPublisher failingPublisher(ServiceBusSenderClient measurementsSender) {
            MeasurementPublisher real = new ServiceBusMeasurementPublisher(measurementsSender);
            return measurements -> {
                if (failing.get()) {
                    throw new IllegalStateException("Service Bus unavailable (test)");
                }
                real.publish(measurements);
            };
        }
    }

    @Autowired
    IngestionHealthIndicator health;

    private final JdbcTemplate admin = DecoderDatabase.admin();
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void everyPersistedMeasurementIsPublishedWithItsPropertiesThenCheckpointed() {
        // 50 measurements of 4 devices; keys repeat across devices, as device message ids are only unique per device.
        send(50, "pub", i -> "key-" + (i / 4));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(measurements("pub")).isEqualTo(50));
        List<ServiceBusReceivedMessage> received = receive("pub", 50);

        assertThat(received).hasSize(50);
        assertThat(received).extracting(ServiceBusReceivedMessage::getMessageId).doesNotHaveDuplicates();
        assertThat(received).allSatisfy(m -> {
            String deviceId = (String) m.getApplicationProperties().get("deviceId");
            assertThat(m.getApplicationProperties()).containsEntry("siteId", "paris-01").containsEntry("payloadFormat", "ruuvi-df5");
            assertThat(m.getMessageId()).isEqualTo(ServiceBusMeasurementPublisher.messageId(deviceId,
                    (String) m.getApplicationProperties().get("messageKey")));
        });
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(checkpointedSequenceNumbers()).hasSize(2));
    }

    @Test
    void unmonitoredSitesArePublishedButNotSelectedByTheAlertingSubscription() {
        send(4, "lyon", i -> "key-" + i, "lyon-01");
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(measurements("lyon")).isEqualTo(4));
        await().atMost(Duration.ofSeconds(30)).until(() -> health.health().getStatus().equals(Status.UP));

        assertThat(receive("lyon", 0)).isEmpty();
    }

    @Test
    void publicationFailureDelaysAcknowledgementWithoutDuplicates() throws Exception {
        send(4, "warm", i -> "key-" + i);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(receive("warm", 4)).hasSize(4));
        await().atMost(Duration.ofSeconds(30)).until(() -> checkpointedSequenceNumbers().size() == 2);
        Map<String, Long> before = waitForStableCheckpoints();

        FailingPublisher.failing.set(true);
        try {
            send(20, "outage", i -> "key-" + i);
            // Stored but unpublished: each partition retries its first batch, later batches wait behind it.
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(measurements("outage")).isPositive());
            await().atMost(Duration.ofSeconds(30)).until(() -> health.health().getStatus().equals(Status.DOWN));
            Thread.sleep(3_000);
            assertThat(checkpointedSequenceNumbers()).as("no checkpoint while unpublished").isEqualTo(before);
        } finally {
            FailingPublisher.failing.set(false);
        }

        assertThat(receive("outage", 20)).hasSize(20);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(measurements("outage")).isEqualTo(20));
        await().atMost(Duration.ofSeconds(30)).until(() -> health.health().getStatus().equals(Status.UP));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(checkpointedSequenceNumbers())
                .allSatisfy((partition, seq) -> assertThat(seq).isGreaterThan(before.getOrDefault(partition, -1L))));
        assertThat(admin.queryForObject("""
                SELECT count(*) - count(DISTINCT (device_id, message_key)) FROM sdp.measurements WHERE device_id LIKE ?
                """, Integer.class, "it-" + run + "-outage-%")).as("no duplicate measurement").isZero();
    }

    private void send(int count, String scenario, java.util.function.IntFunction<String> key) {
        send(count, scenario, key, "paris-01");
    }

    /** 4 devices, each pinned to one of the 2 partitions (IoT Hub also keeps a device on one partition). */
    private void send(int count, String scenario, java.util.function.IntFunction<String> key, String siteId) {
        try (EventHubProducerClient producer = new EventHubClientBuilder()
                .connectionString(eventHubs.eventHubsConnectionString(), EventHubsEmulator.EVENT_HUB)
                .buildProducerClient()) {
            for (int i = 0; i < count; i++) {
                String deviceId = "it-" + run + "-" + scenario + "-" + (i % 4);
                EventData event = new EventData(HexFormat.of().parseHex(RUUVI_DF5));
                event.getRawAmqpMessage().getMessageAnnotations().put("iothub-connection-device-id", deviceId);
                event.getRawAmqpMessage().getMessageAnnotations().put("iothub-enqueuedtime", Date.from(Instant.now()));
                event.getRawAmqpMessage().getProperties().setMessageId(new AmqpMessageId(key.apply(i)));
                event.getProperties().put("siteId", siteId);
                producer.send(List.of(event), new SendOptions().setPartitionId(String.valueOf(i % 4 % 2)));
            }
        }
    }

    /** Messages of this run's scenario on the alerting subscription (others are completed and ignored). */
    private List<ServiceBusReceivedMessage> receive(String scenario, int expected) {
        String prefix = "it-" + run + "-" + scenario + "-";
        List<ServiceBusReceivedMessage> result = new ArrayList<>();
        try (ServiceBusReceiverClient receiver = new ServiceBusClientBuilder().connectionString(serviceBus.connectionString())
                .receiver().topicName(ServiceBusEmulator.TOPIC).subscriptionName(ServiceBusEmulator.SUBSCRIPTION)
                .receiveMode(ServiceBusReceiveMode.RECEIVE_AND_DELETE).buildClient()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(expected == 0 ? 10 : 45).toNanos();
            while (System.nanoTime() < deadline && (expected == 0 || result.size() < expected)) {
                receiver.receiveMessages(100, Duration.ofSeconds(2)).forEach(m -> {
                    if (String.valueOf(m.getApplicationProperties().get("deviceId")).startsWith(prefix)) {
                        result.add(m);
                    }
                });
            }
        }
        return result;
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
}
