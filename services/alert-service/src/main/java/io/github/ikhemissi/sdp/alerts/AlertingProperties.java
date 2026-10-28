package io.github.ikhemissi.sdp.alerts;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Alert service configuration (M5 design D3, implementation guidelines §9).
 *
 * @param thresholdCacheTtl    how long a device's thresholds are cached (changes apply within this delay)
 * @param reminderInterval     a reminder is sent after this long without email while the breach continues
 * @param ongoingBreachWindow  the breach is ongoing while the last breaching measurement is at most this old
 */
@ConfigurationProperties("sdp.alerting")
public record AlertingProperties(
        @DefaultValue ServiceBus servicebus,
        @DefaultValue Email email,
        @DefaultValue("http://localhost:5173") String portalBaseUrl,
        @DefaultValue("30s") Duration thresholdCacheTtl,
        @DefaultValue("60m") Duration reminderInterval,
        @DefaultValue("5m") Duration ongoingBreachWindow,
        @DefaultValue Notifications notifications) {

    /**
     * @param fullyQualifiedNamespace e.g. {@code sbns-demo.servicebus.windows.net}
     * @param connectionString        Service Bus emulator only
     */
    public record ServiceBus(
            @DefaultValue("true") boolean enabled,
            String fullyQualifiedNamespace,
            @DefaultValue("measurements") String topic,
            @DefaultValue("alerting") String subscription,
            @DefaultValue("8") int maxConcurrentCalls,
            @DefaultValue("50") int prefetch,
            String connectionString) {
    }

    /**
     * @param endpoint Communication Services endpoint, e.g. {@code https://acs-demo.europe.communication.azure.com}
     * @param sender   sender address of the linked email domain, e.g. {@code DoNotReply@<domain>}
     * @param timeZone time zone of the times shown in emails
     */
    public record Email(String endpoint, String sender, @DefaultValue("Europe/Paris") ZoneId timeZone) {
    }

    /**
     * @param retryFor how long a failing email is retried before it is recorded as failed
     */
    public record Notifications(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration pollInterval,
            @DefaultValue("30m") Duration retryFor,
            @DefaultValue("30s") Duration initialBackoff,
            @DefaultValue("5m") Duration maxBackoff) {
    }
}
