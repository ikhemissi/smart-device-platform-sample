package io.github.ikhemissi.sdp.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import com.azure.messaging.servicebus.models.SubQueue;
import io.github.ikhemissi.sdp.alerts.TestDoubles.RecordingEmails;
import io.github.ikhemissi.sdp.testsupport.ServiceBusEmulator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M5 task 4.1 — the service consumes the {@code alerting} subscription (Service Bus emulator with the monitored-devices
 * filter {@code siteId = 'paris-01'}), opens alerts and emails them; unmonitored sites never alert; messages that can
 * never be processed are dead-lettered.
 */
@SpringBootTest(properties = {
        "sdp.alerting.notifications.poll-interval=500ms",
        "sdp.alerting.threshold-cache-ttl=0s"})
@Import(TestDoubles.class)
class MeasurementProcessorIT {

    private static ServiceBusEmulator serviceBus;
    private static final String RUN = UUID.randomUUID().toString().substring(0, 6);
    private static final String PARIS = "paris-01-proc-" + RUN;
    private static final String LYON = "lyon-01-proc-" + RUN;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        serviceBus = ServiceBusEmulator.start("siteId = 'paris-01'");
        AlertingDatabase.register(registry);
        registry.add("sdp.alerting.servicebus.connection-string", serviceBus::connectionString);
    }

    @AfterAll
    static void stop() {
        if (serviceBus != null) {
            serviceBus.close();
        }
    }

    @BeforeAll
    static void thresholds() {
        JdbcTemplate admin = AlertingDatabase.admin();
        admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('paris-01', 'Paris'), ('lyon-01', 'Lyon') ON CONFLICT DO NOTHING");
        for (String device : new String[] {PARIS, LYON}) {
            admin.update("""
                    INSERT INTO sdp.devices (device_id, site_id, display_name, last_payload_format, first_seen_at, last_seen_at)
                    VALUES (?, ?, 'Cold room', 'ruuvi-df5', now(), now())""", device, device.equals(PARIS) ? "paris-01" : "lyon-01");
            UUID id = UUID.randomUUID();
            admin.update("""
                    INSERT INTO sdp.alert_thresholds (id, device_id, metric, max_value, recipients, created_by, updated_by)
                    VALUES (?, ?, 'temperature', 8, ARRAY['ops@example.com'], 'oid', 'oid')""", id, device);
            admin.update("INSERT INTO sdp.alert_threshold_state (threshold_id) VALUES (?)", id);
        }
    }

    @Autowired
    RecordingEmails emails;

    private final JdbcTemplate admin = AlertingDatabase.admin();

    @Test
    void monitoredBreachOpensAnAlertAndSendsItsEmailWhileUnmonitoredSitesNeverAlert() {
        try (ServiceBusSenderClient sender = sender()) {
            sender.sendMessage(measurement(LYON, "lyon-01", "12"));
            sender.sendMessage(measurement(PARIS, "paris-01", "12"));
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(emails.sent).anySatisfy(e -> assertThat(e.subject()).contains("above 8 °C")));
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.alert_events WHERE device_id = ? AND status = 'OPEN'", Integer.class, PARIS)).isOne();
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.alert_events WHERE device_id = ?", Integer.class, LYON))
                .as("lyon-01 is not selected by the subscription filter").isZero();
        assertThat(admin.queryForObject("SELECT n.status FROM sdp.alert_notifications n JOIN sdp.alert_events a ON a.id = n.alert_id WHERE a.device_id = ?",
                String.class, PARIS)).isEqualTo("SENT");
    }

    @Test
    void messagesThatCanNeverBeProcessedAreDeadLettered() {
        String id = "invalid-" + RUN;
        try (ServiceBusSenderClient sender = sender()) {
            ServiceBusMessage invalid = new ServiceBusMessage("{\"schemaVersion\":99}").setMessageId(id);
            invalid.getApplicationProperties().put("siteId", "paris-01");
            sender.sendMessage(invalid);
        }

        try (ServiceBusReceiverClient deadLetters = new ServiceBusClientBuilder().connectionString(serviceBus.connectionString())
                .receiver().topicName(ServiceBusEmulator.TOPIC).subscriptionName(ServiceBusEmulator.SUBSCRIPTION)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE).receiveMode(ServiceBusReceiveMode.RECEIVE_AND_DELETE).buildClient()) {
            List<ServiceBusReceivedMessage> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                deadLetters.receiveMessages(10, Duration.ofSeconds(2)).forEach(received::add);
                assertThat(received).anySatisfy(dead -> {
                    assertThat(dead.getMessageId()).isEqualTo(id);
                    assertThat(dead.getDeadLetterReason()).isEqualTo("InvalidMeasurement");
                });
            });
        }
    }

    private static ServiceBusSenderClient sender() {
        return new ServiceBusClientBuilder().connectionString(serviceBus.connectionString())
                .sender().topicName(ServiceBusEmulator.TOPIC).buildClient();
    }

    /** As published by the decoder (ADR-0009). */
    private static ServiceBusMessage measurement(String device, String site, String temperature) {
        Instant now = Instant.now();
        ServiceBusMessage message = new ServiceBusMessage("""
                {"schemaVersion":1,"deviceId":"%s","siteId":"%s","payloadFormat":"ruuvi-df5","measuredAt":"%s",
                 "receivedAt":"%s","messageKey":"k-%s","metrics":{"temperature":%s}}""".formatted(device, site, now, now, RUN, temperature))
                .setMessageId(device + "-" + RUN).setContentType("application/json");
        message.getApplicationProperties().put("deviceId", device);
        message.getApplicationProperties().put("siteId", site);
        message.getApplicationProperties().put("payloadFormat", "ruuvi-df5");
        return message;
    }
}
