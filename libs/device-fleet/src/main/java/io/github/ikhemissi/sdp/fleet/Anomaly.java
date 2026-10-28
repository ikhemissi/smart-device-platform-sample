package io.github.ikhemissi.sdp.fleet;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A forced value for one metric of one device during a time window, e.g. {@code paris-01-ruuvi5-001:temperature=12:5m}
 * (starts when the simulator starts) or {@code paris-01-ruuvi5-001:temperature=12:5m:2m} (starts 2 minutes later).
 */
public record Anomaly(String deviceId, String metric, double value, Duration duration, Duration delay) {

    private static final Pattern SPEC = Pattern.compile(
            "^(?<device>[^:=\\s]+):(?<metric>[a-z0-9_]+)=(?<value>-?\\d+(?:\\.\\d+)?):(?<duration>\\d+[smh])(?::(?<delay>\\d+[smh]))?$");

    public static Anomaly parse(String spec) {
        Matcher m = SPEC.matcher(spec.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "invalid anomaly '" + spec + "', expected <deviceId>:<metric>=<value>:<duration>[:<delay>] e.g. paris-01-ruuvi5-001:temperature=12:5m");
        }
        return new Anomaly(m.group("device"), m.group("metric"), Double.parseDouble(m.group("value")),
                duration(m.group("duration")), m.group("delay") == null ? Duration.ZERO : duration(m.group("delay")));
    }

    public boolean isActive(String deviceId, Instant simulatorStart, Instant now) {
        Instant from = simulatorStart.plus(delay);
        return this.deviceId.equals(deviceId) && !now.isBefore(from) && now.isBefore(from.plus(duration));
    }

    private static Duration duration(String text) {
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return switch (text.charAt(text.length() - 1)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            default -> Duration.ofHours(amount);
        };
    }
}
