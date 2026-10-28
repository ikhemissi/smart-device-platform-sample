package io.github.ikhemissi.sdp.deviceapi.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Points of one metric within one cache chunk, as compact parallel arrays (epoch milliseconds and values; buckets also
 * carry min/max/count). Serialised as JSON in the history cache.
 */
public record Chunk(List<Long> t, List<Double> v, List<Double> min, List<Double> max, List<Long> n) {

    static Chunk raw() {
        return new Chunk(new ArrayList<>(), new ArrayList<>(), null, null, null);
    }

    static Chunk buckets() {
        return new Chunk(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    void addRaw(Instant at, double value) {
        t.add(at.toEpochMilli());
        v.add(value);
    }

    /** {@code v} holds the bucket average. */
    void addBucket(Instant at, double minimum, double average, double maximum, long count) {
        t.add(at.toEpochMilli());
        min.add(minimum);
        v.add(average);
        max.add(maximum);
        n.add(count);
    }

    void appendTo(List<Series.Point> points, Instant from, Instant to) {
        for (int i = 0; i < t.size(); i++) {
            Instant at = Instant.ofEpochMilli(t.get(i));
            if (!at.isBefore(from) && at.isBefore(to)) {
                points.add(n == null ? Series.Point.raw(at, v.get(i))
                        : Series.Point.bucket(at, min.get(i), v.get(i), max.get(i), n.get(i)));
            }
        }
    }
}
