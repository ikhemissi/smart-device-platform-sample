package io.github.ikhemissi.sdp.alerts.notifications;

import io.github.ikhemissi.sdp.alerts.AlertMetrics;
import io.github.ikhemissi.sdp.alerts.AlertingProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued emails (outbox) and queues hourly reminders (spec threshold-alerting: "No flooding and reminders",
 * "Notification reliability"). Each email is claimed with a row lock, so replicas never send the same email twice. A
 * failing email is retried with exponential back-off for {@code retry-for} (30 min), then recorded as failed on the
 * alert; alert state changes never wait for emails.
 */
@Service
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private final NotificationStore store;
    private final EmailRenderer renderer;
    private final EmailGateway gateway;
    private final TransactionTemplate tx;
    private final AlertingProperties properties;
    private final AlertMetrics metrics;
    private final Clock clock;

    public NotificationSender(NotificationStore store, EmailRenderer renderer, EmailGateway gateway, TransactionTemplate tx,
                              AlertingProperties properties, AlertMetrics metrics, Clock clock) {
        this.store = store;
        this.renderer = renderer;
        this.gateway = gateway;
        this.tx = tx;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sdp.alerting.notifications.poll-interval:5s}", initialDelayString = "5s")
    void scheduledSend() {
        if (!properties.notifications().enabled()) {
            return;
        }
        try {
            sendDue();
        } catch (RuntimeException e) {
            log.warn("Sending notifications failed: {}", e.toString());
        }
    }

    /** Reminders are due at most once an hour per alert: checked every minute. */
    @Scheduled(fixedDelayString = "${sdp.alerting.notifications.reminder-check-interval:60s}", initialDelayString = "10s")
    void scheduledReminders() {
        if (!properties.notifications().enabled()) {
            return;
        }
        try {
            queueReminders();
        } catch (RuntimeException e) {
            log.warn("Queueing reminders failed: {}", e.toString());
        }
    }

    /** Sends every due email; returns how many were processed (sent, retried or failed). */
    public int sendDue() {
        int processed = 0;
        while (Boolean.TRUE.equals(tx.execute(status -> sendNext()))) {
            processed++;
        }
        return processed;
    }

    private boolean sendNext() {
        Instant now = clock.instant();
        Optional<PendingNotification> claimed = store.claimNext(now);
        if (claimed.isEmpty()) {
            return false;
        }
        PendingNotification n = claimed.get();
        try {
            gateway.send(renderer.render(n));
            store.markSent(n, clock.instant());
            metrics.notification(n.kind(), "sent");
            log.info("{} email of alert {} sent to {} recipient(s)", n.kind(), n.alertId(), n.recipients().size());
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            Duration backoff = backoff(n.attempts());
            Instant next = now.plus(backoff);
            if (next.isAfter(n.createdAt().plus(properties.notifications().retryFor()))) {
                store.markFailed(n.id(), error);
                metrics.notification(n.kind(), "failed");
                log.warn("{} email of alert {} failed after {} attempt(s): {}", n.kind(), n.alertId(), n.attempts() + 1, error);
            } else {
                store.markAttemptFailed(n.id(), error, next);
                metrics.notification(n.kind(), "retry");
                log.warn("{} email of alert {} failed (attempt {}), retrying in {}: {}", n.kind(), n.alertId(), n.attempts() + 1, backoff, error);
            }
        }
        return true;
    }

    /** Queues a reminder for open alerts still breaching and not emailed for {@code reminder-interval}. */
    public int queueReminders() {
        Instant now = clock.instant();
        Integer queued = tx.execute(status -> store.queueReminders(now.minus(properties.ongoingBreachWindow()),
                now.minus(properties.reminderInterval()), now));
        if (queued != null && queued > 0) {
            log.info("{} reminder(s) queued", queued);
        }
        return queued == null ? 0 : queued;
    }

    Duration backoff(int attempts) {
        Duration initial = properties.notifications().initialBackoff();
        Duration max = properties.notifications().maxBackoff();
        Duration backoff = initial.multipliedBy(1L << Math.min(attempts, 20));
        return backoff.compareTo(max) > 0 ? max : backoff;
    }
}
