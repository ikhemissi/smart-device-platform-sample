package io.github.ikhemissi.sdp.alerts.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.alerts.AlertingProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * M5 task 4.4 — email content (spec threshold-alerting: "Email content"). Rendered emails are compared with the
 * committed snapshots in {@code src/test/resources/snapshots}; regenerate with {@code -Dsdp.snapshots.update=true}.
 */
class EmailRendererTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/snapshots");
    private static final Instant OPENED = Instant.parse("2026-10-28T13:05:00Z");

    private final EmailRenderer renderer = new EmailRenderer(new AlertingProperties(
            new AlertingProperties.ServiceBus(false, null, "measurements", "alerting", 8, 50, null),
            new AlertingProperties.Email("https://acs.example", "DoNotReply@example.azurecomm.net", ZoneId.of("Europe/Paris")),
            "https://portal.example.com/", Duration.ofSeconds(30), Duration.ofMinutes(60), Duration.ofMinutes(5),
            new AlertingProperties.Notifications(true, Duration.ofSeconds(5), Duration.ofMinutes(30), Duration.ofSeconds(30), Duration.ofMinutes(5))));

    static PendingNotification notification(String kind, String value) {
        boolean resolved = PendingNotification.RESOLVED.equals(kind);
        return new PendingNotification(1, kind, List.of("ops@example.com", "cold-room@example.com"), new BigDecimal(value), 0,
                OPENED, 42, "paris-01-ruuvi5-001", "Cold room", "paris-01", "Paris warehouse", "temperature", "Air temperature",
                "°C", new BigDecimal("2"), new BigDecimal("8.0"), "ABOVE", OPENED,
                resolved ? OPENED.plus(Duration.ofMinutes(7)) : null, new BigDecimal("12.40"), OPENED.plus(Duration.ofMinutes(65)));
    }

    @Test
    void alertEmailStatesTheBreachAndLinksToTheDevicePage() {
        EmailGateway.Email email = renderer.render(notification(PendingNotification.ALERT, "12"));

        assertThat(email.subject()).isEqualTo("Alert: Cold room – Air temperature above 8 °C");
        assertThat(email.recipients()).containsExactly("ops@example.com", "cold-room@example.com");
        assertThat(email.text()).contains("Cold room (paris-01-ruuvi5-001)").contains("Paris warehouse (paris-01)")
                .contains("12 °C").contains("12.4 °C").contains("between 2 °C and 8 °C")
                .contains("28 Oct 2026, 14:05:00 CET").contains("https://portal.example.com/devices/paris-01-ruuvi5-001");
        assertThat(email.html()).contains("href=\"https://portal.example.com/devices/paris-01-ruuvi5-001\"");
    }

    @Test
    void htmlEscapesDeviceNames() {
        PendingNotification n = notification(PendingNotification.ALERT, "12");
        PendingNotification hostile = new PendingNotification(n.id(), n.kind(), n.recipients(), n.value(), n.attempts(), n.createdAt(),
                n.alertId(), n.deviceId(), "<script>x</script>", n.siteId(), n.siteName(), n.metric(), n.metricDescription(), n.unit(),
                n.thresholdMin(), n.thresholdMax(), n.direction(), n.openedAt(), n.closedAt(), n.worstValue(), n.lastBreachAt());

        assertThat(renderer.render(hostile).html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALERT", "REMINDER", "RESOLVED"})
    void emailsMatchTheirSnapshots(String kind) throws IOException {
        EmailGateway.Email email = renderer.render(notification(kind, PendingNotification.RESOLVED.equals(kind) ? "5.5" : "12"));
        String rendered = "Subject: " + email.subject() + "\n\n" + email.text();

        assertSnapshot(kind.toLowerCase() + ".txt", rendered);
        assertSnapshot(kind.toLowerCase() + ".html", email.html());
    }

    private static void assertSnapshot(String name, String actual) throws IOException {
        Path file = SNAPSHOTS.resolve(name);
        if (Boolean.getBoolean("sdp.snapshots.update") || !Files.exists(file)) {
            Files.createDirectories(SNAPSHOTS);
            Files.writeString(file, actual);
        }
        assertThat(actual).as("snapshot " + name + " (regenerate with -Dsdp.snapshots.update=true)").isEqualTo(Files.readString(file));
    }
}
