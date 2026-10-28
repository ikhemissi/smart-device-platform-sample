package io.github.ikhemissi.sdp.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.alerts.TestDoubles.MutableClock;
import io.github.ikhemissi.sdp.alerts.TestDoubles.RecordingEmails;
import io.github.ikhemissi.sdp.alerts.evaluation.AlertEvaluator;
import io.github.ikhemissi.sdp.alerts.evaluation.AlertEvaluator.Outcome;
import io.github.ikhemissi.sdp.alerts.measurements.Measurement;
import io.github.ikhemissi.sdp.alerts.notifications.EmailGateway;
import io.github.ikhemissi.sdp.alerts.notifications.NotificationSender;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M5 tasks 4.2 and 4.3 — the per-threshold alert lifecycle and its emails against PostgreSQL, with a fake clock and a
 * recording email gateway (spec threshold-alerting). Measurements are given to the evaluator directly; consumption
 * from Service Bus is covered by {@link MeasurementProcessorIT}.
 */
@SpringBootTest(properties = {
        "sdp.alerting.servicebus.enabled=false",
        "sdp.alerting.notifications.enabled=false",
        "sdp.alerting.threshold-cache-ttl=0s"})
@Import(TestDoubles.class)
class AlertLifecycleIT {

    private static final AtomicInteger DEVICES = new AtomicInteger();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AlertingDatabase.register(registry);
    }

    @Autowired
    AlertEvaluator evaluator;
    @Autowired
    NotificationSender sender;
    @Autowired
    MutableClock clock;
    @Autowired
    RecordingEmails emails;

    private final JdbcTemplate admin = AlertingDatabase.admin();
    private final Instant t0 = Instant.parse("2026-10-28T09:00:00Z");
    private String device;
    private UUID threshold;

    @AfterEach
    void reset() {
        emails.failing = false;
    }

    /** A monitored cold room: temperature between 2 and 8 °C. */
    private void coldRoom() {
        device = "it-alert-" + DEVICES.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
        admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('alerts-01', 'Alerting site') ON CONFLICT DO NOTHING");
        admin.update("""
                INSERT INTO sdp.devices (device_id, site_id, display_name, last_payload_format, first_seen_at, last_seen_at)
                VALUES (?, 'alerts-01', 'Cold room', 'ruuvi-df5', now(), now())""", device);
        threshold = UUID.randomUUID();
        admin.update("""
                INSERT INTO sdp.alert_thresholds (id, device_id, metric, min_value, max_value, recipients, created_by, updated_by)
                VALUES (?, ?, 'temperature', 2, 8, ARRAY['ops-' || ? || '@example.com'], 'oid', 'oid')""", threshold, device, device);
        admin.update("INSERT INTO sdp.alert_threshold_state (threshold_id) VALUES (?)", threshold);
        clock.set(t0);
    }

    private Measurement reading(Instant at, String temperature) {
        return new Measurement(device, "alerts-01", "ruuvi-df5", at, at.plusSeconds(2), "k-" + at.toEpochMilli(),
                Map.of("temperature", new BigDecimal(temperature), "humidity", new BigDecimal("40")));
    }

    private Outcome evaluate(Measurement measurement) {
        List<AlertEvaluator.Evaluation> evaluations = evaluator.evaluate(measurement);
        assertThat(evaluations).hasSize(1);
        return evaluations.getFirst().outcome();
    }

    /** Reports {@code temperature} every 30 s for {@code duration}, running the email sender as the service would. */
    private void report(Duration duration, String temperature) {
        Instant end = clock.instant().plus(duration);
        while (!clock.instant().isAfter(end)) {
            evaluator.evaluate(reading(clock.instant(), temperature));
            sender.queueReminders();
            sender.sendDue();
            clock.advance(Duration.ofSeconds(30));
        }
    }

    private List<EmailGateway.Email> emailsOfDevice() {
        return emails.sent.stream().filter(e -> e.recipients().contains("ops-" + device + "@example.com")).toList();
    }

    private List<Map<String, Object>> alerts() {
        return admin.queryForList("SELECT * FROM sdp.alert_events WHERE threshold_id = ? ORDER BY id", threshold);
    }

    @Test
    void temperatureAboveMaximumOpensAnAlertAndEmailsTheRecipients() {
        coldRoom();

        assertThat(evaluate(reading(t0, "12"))).isEqualTo(Outcome.OPENED);
        sender.sendDue();

        List<Map<String, Object>> alerts = alerts();
        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.get("status")).isEqualTo("OPEN");
            assertThat(a.get("direction")).isEqualTo("ABOVE");
            assertThat((BigDecimal) a.get("threshold_max")).isEqualByComparingTo("8");
            assertThat((BigDecimal) a.get("first_value")).isEqualByComparingTo("12");
            assertThat(a.get("last_notified_at")).isNotNull();
        });
        assertThat(emailsOfDevice()).singleElement()
                .satisfies(e -> assertThat(e.subject()).contains("Cold room").contains("Air temperature").contains("above 8 °C"));
    }

    @Test
    void continuousBreachForThirtyMinutesSendsExactlyOneEmail() {
        coldRoom();

        report(Duration.ofMinutes(30), "12");

        assertThat(emailsOfDevice()).hasSize(1);
        assertThat(alerts()).hasSize(1);
    }

    @Test
    void continuousBreachForSeventyMinutesSendsOneAlertAndOneReminder() {
        coldRoom();

        report(Duration.ofMinutes(70), "12.5");

        assertThat(emailsOfDevice()).extracting(EmailGateway.Email::subject)
                .satisfiesExactly(
                        s -> assertThat(s).startsWith("Alert: Cold room"),
                        s -> assertThat(s).startsWith("Still alerting: Cold room").contains("above 8 °C"));
        assertThat((BigDecimal) alerts().getFirst().get("worst_value")).isEqualByComparingTo("12.5");
    }

    @Test
    void backToNormalClosesTheAlertWithOneResolutionEmail() {
        coldRoom();
        report(Duration.ofMinutes(2), "12");

        report(Duration.ofMinutes(5), "5");

        assertThat(alerts()).singleElement().satisfies(a -> {
            assertThat(a.get("status")).isEqualTo("CLOSED");
            assertThat(a.get("closed_at")).isNotNull();
            assertThat((BigDecimal) a.get("last_value")).isEqualByComparingTo("5");
        });
        assertThat(emailsOfDevice()).extracting(EmailGateway.Email::subject)
                .satisfiesExactly(s -> assertThat(s).startsWith("Alert:"), s -> assertThat(s).startsWith("Back to normal:"));
    }

    @Test
    void duplicateDeliveryChangesNothing() {
        coldRoom();
        Measurement breach = reading(t0, "12");

        assertThat(evaluate(breach)).isEqualTo(Outcome.OPENED);
        assertThat(evaluate(breach)).isEqualTo(Outcome.DUPLICATE);
        sender.sendDue();

        assertThat(alerts()).hasSize(1);
        assertThat(emailsOfDevice()).hasSize(1);
    }

    @Test
    void olderMeasurementsAreIgnoredForStateChanges() {
        coldRoom();
        assertThat(evaluate(reading(t0.plusSeconds(60), "12"))).isEqualTo(Outcome.OPENED);
        assertThat(evaluate(reading(t0.plusSeconds(120), "5"))).isEqualTo(Outcome.RESOLVED);

        assertThat(evaluate(reading(t0.plusSeconds(90), "13"))).as("late delivery").isEqualTo(Outcome.IGNORED_OLDER);

        assertThat(alerts()).singleElement().satisfies(a -> assertThat(a.get("status")).isEqualTo("CLOSED"));
    }

    @Test
    void concurrentFirstBreachesOnTwoReplicasOpenOneAlert() throws Exception {
        coldRoom();
        int replicas = 8;
        CyclicBarrier start = new CyclicBarrier(replicas);
        List<Future<List<AlertEvaluator.Evaluation>>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(replicas)) {
            for (int i = 0; i < replicas; i++) {
                Measurement breach = reading(t0.plusMillis(i), "12");
                results.add(pool.submit(() -> {
                    start.await();
                    return evaluator.evaluate(breach);
                }));
            }
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<List<AlertEvaluator.Evaluation>> result : results) {
            outcomes.add(result.get().getFirst().outcome());
        }

        assertThat(outcomes).containsOnlyOnce(Outcome.OPENED);
        assertThat(alerts()).hasSize(1);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM sdp.alert_notifications n JOIN sdp.alert_events a ON a.id = n.alert_id
                WHERE a.threshold_id = ?""", Integer.class, threshold)).isOne();
    }

    @Test
    void emailOutageIsRetriedThenRecordedWithoutBlockingTheAlert() {
        coldRoom();
        emails.failing = true;

        assertThat(evaluate(reading(t0, "12"))).isEqualTo(Outcome.OPENED);
        for (int minute = 0; minute <= 35; minute++) {
            clock.set(t0.plus(Duration.ofMinutes(minute)));
            sender.sendDue();
        }

        Map<String, Object> notification = admin.queryForMap("""
                SELECT n.status, n.attempts, n.last_error FROM sdp.alert_notifications n JOIN sdp.alert_events a ON a.id = n.alert_id
                WHERE a.threshold_id = ?""", threshold);
        assertThat(notification.get("status")).isEqualTo("FAILED");
        assertThat((Integer) notification.get("attempts")).isGreaterThan(3);
        assertThat((String) notification.get("last_error")).contains("email service unavailable");
        assertThat(alerts()).singleElement().satisfies(a -> assertThat(a.get("status")).isEqualTo("OPEN"));

        assertThat(evaluate(reading(t0.plus(Duration.ofMinutes(36)), "5"))).as("state changes go on").isEqualTo(Outcome.RESOLVED);
        assertThat(emailsOfDevice()).isEmpty();
    }

    @Test
    void disabledThresholdsAndOtherMetricsAreNotEvaluated() {
        coldRoom();
        admin.update("UPDATE sdp.alert_thresholds SET enabled = false WHERE id = ?", threshold);

        assertThat(evaluator.evaluate(reading(t0, "12"))).isEmpty();
        assertThat(alerts()).isEmpty();
    }
}
