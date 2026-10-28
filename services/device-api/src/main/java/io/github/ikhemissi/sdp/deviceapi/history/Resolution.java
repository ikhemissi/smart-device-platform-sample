package io.github.ikhemissi.sdp.deviceapi.history;

import java.time.Duration;
import java.time.Instant;

/**
 * Series resolution: raw points or one of the fixed bucket sizes (aligned to a common origin so buckets line up across
 * requests and cache chunks). Cache chunks are one hour for raw and minute buckets, one UTC day otherwise.
 */
public enum Resolution {
    RAW("raw", null, Duration.ofHours(1)),
    MIN_1("1m", Duration.ofMinutes(1), Duration.ofHours(1)),
    MIN_5("5m", Duration.ofMinutes(5), Duration.ofHours(1)),
    MIN_15("15m", Duration.ofMinutes(15), Duration.ofHours(1)),
    HOUR_1("1h", Duration.ofHours(1), Duration.ofDays(1)),
    HOUR_6("6h", Duration.ofHours(6), Duration.ofDays(1)),
    DAY_1("1d", Duration.ofDays(1), Duration.ofDays(1));

    private final String id;
    private final Duration bucket;
    private final Duration chunk;

    Resolution(String id, Duration bucket, Duration chunk) {
        this.id = id;
        this.bucket = bucket;
        this.chunk = chunk;
    }

    public String id() {
        return id;
    }

    /** Bucket size, {@code null} for raw points. */
    public Duration bucket() {
        return bucket;
    }

    public Duration chunk() {
        return chunk;
    }

    /** The smallest bucket size producing at most {@code maxPoints} buckets over {@code [from, to)} (the largest otherwise). */
    public static Resolution bucketsFor(Instant from, Instant to, int maxPoints) {
        long seconds = Duration.between(from, to).toSeconds();
        for (Resolution r : values()) {
            if (r != RAW && Math.ceilDiv(seconds, r.bucket.toSeconds()) <= maxPoints) {
                return r;
            }
        }
        return DAY_1;
    }

    /** Start of the bucket (or chunk) of size {@code size} containing {@code t}; sizes divide a day, so UTC-aligned. */
    static Instant floor(Instant t, Duration size) {
        long s = size.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(t.toEpochMilli(), s) * s);
    }
}
