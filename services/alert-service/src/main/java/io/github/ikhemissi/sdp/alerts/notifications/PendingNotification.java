package io.github.ikhemissi.sdp.alerts.notifications;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A queued email of an alert, with everything needed to write it. */
public record PendingNotification(
        long id,
        String kind,
        List<String> recipients,
        BigDecimal value,
        int attempts,
        Instant createdAt,
        long alertId,
        String deviceId,
        String deviceName,
        String siteId,
        String siteName,
        String metric,
        String metricDescription,
        String unit,
        BigDecimal thresholdMin,
        BigDecimal thresholdMax,
        String direction,
        Instant openedAt,
        Instant closedAt,
        BigDecimal worstValue,
        Instant lastBreachAt) {

    public static final String ALERT = "ALERT";
    public static final String REMINDER = "REMINDER";
    public static final String RESOLVED = "RESOLVED";
}
