package io.github.ikhemissi.sdp.alerts;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import org.springframework.stereotype.Component;

/**
 * Alerting metrics, exported to Application Insights: alerts opened and closed, notifications by kind and outcome,
 * measurements evaluated.
 */
@Component
public class AlertMetrics {

    public static final AttributeKey<String> METRIC = AttributeKey.stringKey("sdp.metric");
    public static final AttributeKey<String> KIND = AttributeKey.stringKey("sdp.notification.kind");
    public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("sdp.notification.outcome");

    private final LongCounter opened;
    private final LongCounter closed;
    private final LongCounter notifications;
    private final LongCounter evaluated;

    public AlertMetrics(OpenTelemetry openTelemetry) {
        var meter = openTelemetry.getMeter("sdp-alert-service");
        opened = meter.counterBuilder("sdp.alerts.opened").setDescription("Alerts opened").setUnit("{alert}").build();
        closed = meter.counterBuilder("sdp.alerts.closed").setDescription("Alerts closed (back to normal)").setUnit("{alert}").build();
        notifications = meter.counterBuilder("sdp.alerts.notifications")
                .setDescription("Alert emails by kind and outcome (sent, retry, failed)").setUnit("{email}").build();
        evaluated = meter.counterBuilder("sdp.alerts.measurements")
                .setDescription("Measurements evaluated against thresholds").setUnit("{measurement}").build();
    }

    public void opened(String metric) {
        opened.add(1, Attributes.of(METRIC, metric));
    }

    public void closed(String metric) {
        closed.add(1, Attributes.of(METRIC, metric));
    }

    public void notification(String kind, String outcome) {
        notifications.add(1, Attributes.of(KIND, kind, OUTCOME, outcome));
    }

    public void evaluated() {
        evaluated.add(1);
    }
}
