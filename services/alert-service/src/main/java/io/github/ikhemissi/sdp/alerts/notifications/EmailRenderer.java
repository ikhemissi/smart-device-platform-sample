package io.github.ikhemissi.sdp.alerts.notifications;

import io.github.ikhemissi.sdp.alerts.AlertingProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Writes alert, reminder and "back to normal" emails (spec threshold-alerting: "Email content"): device name and id,
 * site, metric with unit, current and worst values, threshold, times and a link to the device page. Plain text and
 * HTML from Thymeleaf templates in {@code templates/email}.
 */
@Component
public class EmailRenderer {

    private final TemplateEngine html = engine(TemplateMode.HTML, ".html");
    private final TemplateEngine text = engine(TemplateMode.TEXT, ".txt");
    private final String portalBaseUrl;
    private final DateTimeFormatter times;

    public EmailRenderer(AlertingProperties properties) {
        this.portalBaseUrl = properties.portalBaseUrl().replaceAll("/+$", "");
        this.times = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss z", Locale.ENGLISH).withZone(properties.email().timeZone());
    }

    public EmailGateway.Email render(PendingNotification n) {
        Map<String, Object> model = model(n);
        Context context = new Context(Locale.ENGLISH, model);
        return new EmailGateway.Email(n.recipients(), (String) model.get("subject"),
                text.process("notification", context), html.process("notification", context));
    }

    Map<String, Object> model(PendingNotification n) {
        String device = n.deviceName() == null || n.deviceName().isBlank() ? n.deviceId() : n.deviceName();
        String metric = n.metricDescription() == null ? n.metric() : n.metricDescription();
        String limit = "ABOVE".equals(n.direction()) ? "above " + quantity(n.thresholdMax(), n.unit())
                : "below " + quantity(n.thresholdMin(), n.unit());
        String range = range(n.thresholdMin(), n.thresholdMax(), n.unit());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("kind", n.kind());
        model.put("subject", switch (n.kind()) {
            case PendingNotification.REMINDER -> "Still alerting: " + device + " – " + metric + " " + limit;
            case PendingNotification.RESOLVED -> "Back to normal: " + device + " – " + metric + " " + range;
            default -> "Alert: " + device + " – " + metric + " " + limit;
        });
        model.put("headline", switch (n.kind()) {
            case PendingNotification.REMINDER -> metric + " of " + device + " is still " + limit + ".";
            case PendingNotification.RESOLVED -> metric + " of " + device + " is back " + range + ".";
            default -> metric + " of " + device + " is " + limit + ".";
        });
        model.put("device", n.deviceName() == null || n.deviceName().isBlank() ? n.deviceId() : n.deviceName() + " (" + n.deviceId() + ")");
        model.put("site", n.siteName() == null || n.siteName().equals(n.siteId()) ? n.siteId() : n.siteName() + " (" + n.siteId() + ")");
        model.put("metric", metric + (n.unit() == null ? "" : " (" + n.unit() + ")"));
        model.put("currentValue", quantity(n.value(), n.unit()));
        model.put("worstValue", quantity(n.worstValue(), n.unit()));
        model.put("threshold", thresholdText(n.thresholdMin(), n.thresholdMax(), n.unit()));
        model.put("openedAt", time(n.openedAt()));
        model.put("measuredAt", time(PendingNotification.RESOLVED.equals(n.kind()) ? n.closedAt() : PendingNotification.ALERT.equals(n.kind()) ? n.openedAt() : n.lastBreachAt()));
        model.put("closedAt", n.closedAt() == null ? null : time(n.closedAt()));
        model.put("link", portalBaseUrl + "/devices/" + n.deviceId());
        return model;
    }

    static String quantity(BigDecimal value, String unit) {
        if (value == null) {
            return "–";
        }
        String number = value.stripTrailingZeros().toPlainString();
        return unit == null || unit.isBlank() ? number : number + " " + unit;
    }

    private static String range(BigDecimal min, BigDecimal max, String unit) {
        if (min != null && max != null) {
            return "within " + min.stripTrailingZeros().toPlainString() + "–" + quantity(max, unit);
        }
        return max != null ? "at or below " + quantity(max, unit) : "at or above " + quantity(min, unit);
    }

    private static String thresholdText(BigDecimal min, BigDecimal max, String unit) {
        if (min != null && max != null) {
            return "between " + quantity(min, unit) + " and " + quantity(max, unit);
        }
        return max != null ? "at most " + quantity(max, unit) : "at least " + quantity(min, unit);
    }

    private String time(Instant instant) {
        return instant == null ? "–" : times.format(instant);
    }

    private static TemplateEngine engine(TemplateMode mode, String suffix) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
