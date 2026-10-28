package io.github.ikhemissi.sdp.alerts.evaluation;

import io.github.ikhemissi.sdp.alerts.AlertMetrics;
import io.github.ikhemissi.sdp.alerts.evaluation.AlertStore.LockedThreshold;
import io.github.ikhemissi.sdp.alerts.evaluation.AlertStore.OpenAlert;
import io.github.ikhemissi.sdp.alerts.measurements.Measurement;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-threshold alert state machine (spec threshold-alerting; M5 design D3). Each threshold of the device watching a
 * metric of the measurement is evaluated in its own transaction, holding the lock on the threshold's state row:
 * <ul>
 *   <li>a measurement older than the last one evaluated, or the same measurement delivered again, changes nothing
 *   (at-least-once, out-of-order delivery);</li>
 *   <li>outside the range with no open alert: an alert opens and its email is queued;</li>
 *   <li>outside the range with an open alert: last and worst values are updated, no email (reminders are separate);</li>
 *   <li>back within the range: the alert closes and a single "back to normal" email is queued.</li>
 * </ul>
 * Emails are queued in the same transaction (outbox), so a redelivered message never sends a second email.
 */
@Service
public class AlertEvaluator {

    public static final String ABOVE = "ABOVE";
    public static final String BELOW = "BELOW";

    /** What a measurement did to one threshold. */
    public enum Outcome { OPENED, ONGOING, RESOLVED, NORMAL, IGNORED_OLDER, DUPLICATE, SKIPPED }

    public record Evaluation(UUID thresholdId, Outcome outcome) {
    }

    private final AlertStore store;
    private final ThresholdCache thresholds;
    private final TransactionTemplate tx;
    private final AlertMetrics metrics;
    private final Clock clock;

    public AlertEvaluator(AlertStore store, ThresholdCache thresholds, TransactionTemplate tx, AlertMetrics metrics, Clock clock) {
        this.store = store;
        this.thresholds = thresholds;
        this.tx = tx;
        this.metrics = metrics;
        this.clock = clock;
    }

    public List<Evaluation> evaluate(Measurement measurement) {
        metrics.evaluated();
        List<Evaluation> evaluations = new ArrayList<>();
        for (ThresholdCache.Watched watched : thresholds.thresholds(measurement.deviceId())) {
            if (measurement.metrics().containsKey(watched.metric())) {
                evaluations.add(new Evaluation(watched.id(), tx.execute(status -> evaluate(watched.id(), measurement))));
            }
        }
        return evaluations;
    }

    private Outcome evaluate(UUID thresholdId, Measurement m) {
        Optional<LockedThreshold> locked = store.lock(thresholdId);
        if (locked.isEmpty() || !locked.get().enabled()) {
            return Outcome.SKIPPED;
        }
        LockedThreshold threshold = locked.get();
        BigDecimal value = m.metrics().get(threshold.metric());
        if (value == null) {
            return Outcome.SKIPPED;
        }
        if (threshold.lastEvaluatedAt() != null) {
            if (m.measuredAt().isBefore(threshold.lastEvaluatedAt())) {
                return Outcome.IGNORED_OLDER;
            }
            if (m.measuredAt().equals(threshold.lastEvaluatedAt()) && m.messageKey().equals(threshold.lastMessageKey())) {
                return Outcome.DUPLICATE;
            }
        }
        store.markEvaluated(thresholdId, m.measuredAt(), m.messageKey());

        String breach = breach(threshold, value);
        Optional<OpenAlert> open = store.openAlert(thresholdId);
        if (breach != null && open.isEmpty()) {
            long alertId = store.openAlert(threshold, m.siteId(), breach, value, m.measuredAt());
            store.queueNotification(alertId, "ALERT", threshold.recipients(), value, clock.instant());
            metrics.opened(threshold.metric());
            return Outcome.OPENED;
        }
        if (breach != null) {
            OpenAlert alert = open.get();
            store.recordBreach(alert.id(), value, worst(alert, value), m.measuredAt());
            return Outcome.ONGOING;
        }
        if (open.isPresent()) {
            store.close(open.get().id(), value, m.measuredAt());
            store.queueNotification(open.get().id(), "RESOLVED", threshold.recipients(), value, clock.instant());
            metrics.closed(threshold.metric());
            return Outcome.RESOLVED;
        }
        return Outcome.NORMAL;
    }

    static String breach(LockedThreshold threshold, BigDecimal value) {
        if (threshold.max() != null && value.compareTo(threshold.max()) > 0) {
            return ABOVE;
        }
        if (threshold.min() != null && value.compareTo(threshold.min()) < 0) {
            return BELOW;
        }
        return null;
    }

    /** Worst value in the alert's direction (highest above the maximum, lowest below the minimum). */
    static BigDecimal worst(OpenAlert alert, BigDecimal value) {
        return ABOVE.equals(alert.direction()) ? alert.worstValue().max(value) : alert.worstValue().min(value);
    }
}
