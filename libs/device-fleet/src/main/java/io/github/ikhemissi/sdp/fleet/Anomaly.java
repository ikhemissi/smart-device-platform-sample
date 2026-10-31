package io.github.ikhemissi.sdp.fleet;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A forced value for one metric of one device during a time window, e.g. {@code paris-01-ruuvi5-001:temperature=12:5m}
 * (starts when the simulator starts) or {@code paris-01-ruuvi5-001:temperature=12:5m:2m} (starts 2 minutes later).
 * Without a ramp the value is forced at once and held almost constant (like a faulty sensor); with a ramp,
 * {@code temperature=35~5m:10m}, the readings move to the value over 5 minutes with their usual noise, hold it for 10
 * minutes, then return over 5 minutes (like a room really warming up).
 */
public record Anomaly(String deviceId, String metric, double value, Duration duration, Duration delay, Duration ramp) {

    private static final Pattern SPEC = Pattern.compile(
            "^(?<device>[^:=\\s]+):(?<metric>[a-z0-9_]+)=(?<value>-?\\d+(?:\\.\\d+)?)(?:~(?<ramp>\\d+[smh]))?"
                    + ":(?<duration>\\d+[smh])(?::(?<delay>\\d+[smh]))?$");

    public static Anomaly parse(String spec) {
        Matcher matcher = SPEC.matcher(spec.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid anomaly '" + spec
                    + "', expected <deviceId>:<metric>=<value>[~<ramp>]:<duration>[:<delay>] e.g. paris-01-ruuvi5-001:temperature=12:5m");
        }
        return new Anomaly(matcher.group("device"), matcher.group("metric"), Double.parseDouble(matcher.group("value")),
                duration(matcher.group("duration")), optionalDuration(matcher.group("delay")),
                optionalDuration(matcher.group("ramp")));
    }

    public boolean isActive(String deviceId, Instant simulatorStart, Instant now) {
        return this.deviceId.equals(deviceId) && weight(simulatorStart, now) > 0;
    }

    /** Share of the forced value in the reading at {@code now}: 0 outside the anomaly, 1 while it is held. */
    public double weight(Instant simulatorStart, Instant now) {
        Instant from = simulatorStart.plus(delay);
        // With a ramp the value is held for the whole duration once reached.
        Instant until = from.plus(ramp).plus(duration);
        if (now.isBefore(from) || !now.isBefore(until.plus(ramp))) {
            return 0;
        }
        if (ramp.isZero()) {
            return 1;
        }
        double rampSeconds = ramp.toMillis() / 1000.0;
        if (now.isBefore(from.plus(ramp))) {
            return Duration.between(from, now).toMillis() / 1000.0 / rampSeconds;
        }
        if (now.isBefore(until)) {
            return 1;
        }
        return 1 - Duration.between(until, now).toMillis() / 1000.0 / rampSeconds;
    }

    private static Duration optionalDuration(String text) {
        return text == null ? Duration.ZERO : duration(text);
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
