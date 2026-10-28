package io.github.ikhemissi.sdp.deviceapi.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Chart-ready series of a device over {@code [from, to)}: raw points ({@code resolution = raw}) or buckets of
 * {@code bucketSeconds} with min/avg/max/count. Buckets are aligned to a fixed grid (UTC), so the first bucket may start
 * slightly before {@code from}.
 */
public record Series(String deviceId, Instant from, Instant to, String resolution,
                     @JsonInclude(JsonInclude.Include.NON_NULL) Long bucketSeconds, List<MetricSeries> series) {

    public record MetricSeries(String metric, String unit, List<Point> points) {
    }

    /** A raw value ({@code value}) or a bucket ({@code min}, {@code avg}, {@code max}, {@code count}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Point(Instant t, Double value, Double min, Double avg, Double max, Long count) {

        static Point raw(Instant t, double value) {
            return new Point(t, value, null, null, null, null);
        }

        static Point bucket(Instant t, double min, double avg, double max, long count) {
            return new Point(t, null, min, avg, max, count);
        }
    }
}
