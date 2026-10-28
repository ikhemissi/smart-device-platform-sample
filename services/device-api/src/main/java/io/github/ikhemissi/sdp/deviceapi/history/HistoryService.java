package io.github.ikhemissi.sdp.deviceapi.history;

import io.github.ikhemissi.sdp.deviceapi.cache.HistoryCache;
import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Device history (spec telemetry-history), cache-aside (M3 design D2). Series are cached per metric in aligned chunks:
 * a chunk that ends before the ingestion watermark can no longer change and is kept for a day, other chunks for 30 s,
 * so sliding windows reuse almost everything and new measurements appear within a minute.
 */
@Service
public class HistoryService {

    private static final TypeReference<List<Measurement>> MEASUREMENTS = new TypeReference<>() {
    };
    private static final TypeReference<List<ReportedMetric>> METRICS = new TypeReference<>() {
    };

    private final HistoryRepository repository;
    private final HistoryCache cache;
    private final IngestionWatermark watermark;
    private final HistoryProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    public HistoryService(HistoryRepository repository, HistoryCache cache, IngestionWatermark watermark,
                          HistoryProperties properties, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.watermark = watermark;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public List<Measurement> latest(String deviceId, int count) {
        requireDevice(deviceId);
        return cached("latest:{" + deviceId + "}:" + count, properties.latestTtl(), MEASUREMENTS,
                () -> repository.latest(deviceId, count));
    }

    public List<ReportedMetric> metrics(String deviceId) {
        requireDevice(deviceId);
        return cached("metrics:{" + deviceId + "}", properties.metricsTtl(), METRICS,
                () -> repository.metrics(deviceId, clock.instant().minus(properties.retention())));
    }

    public MeasurementPage range(String deviceId, Instant from, Instant to, String cursor, int pageSize) {
        validateRange(from, to);
        requireDevice(deviceId);
        Cursor after = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        List<HistoryRepository.KeyedMeasurement> rows = repository.range(deviceId, from, to, after, pageSize + 1);
        boolean more = rows.size() > pageSize;
        List<HistoryRepository.KeyedMeasurement> page = more ? rows.subList(0, pageSize) : rows;
        String next = more ? new Cursor(page.getLast().measurement().measuredAt(), page.getLast().messageKey()).encode() : null;
        return new MeasurementPage(page.stream().map(HistoryRepository.KeyedMeasurement::measurement).toList(), next);
    }

    public Series series(String deviceId, List<String> metrics, Instant from, Instant to, int maxPoints) {
        validateRange(from, to);
        requireDevice(deviceId);
        Resolution resolution = repository.countUpTo(deviceId, from, to, maxPoints + 1) <= maxPoints
                ? Resolution.RAW : Resolution.bucketsFor(from, to, maxPoints);
        Map<String, String> units = repository.units(metrics);
        Instant completeBefore = watermark.completeBefore();
        // Buckets are reported from the start of the bucket containing `from`.
        Instant start = resolution == Resolution.RAW ? from : Resolution.floor(from, resolution.bucket());
        List<Series.MetricSeries> series = new ArrayList<>();
        for (String metric : metrics) {
            List<Series.Point> points = new ArrayList<>();
            for (Chunk chunk : chunks(deviceId, metric, resolution, start, to, completeBefore)) {
                chunk.appendTo(points, start, to);
            }
            series.add(new Series.MetricSeries(metric, units.get(metric), points));
        }
        return new Series(deviceId, from, to, resolution.id(),
                resolution.bucket() == null ? null : resolution.bucket().toSeconds(), series);
    }

    /** Chunks covering {@code [from, to)}, from the cache when possible; misses are read in contiguous ranges. */
    private List<Chunk> chunks(String deviceId, String metric, Resolution resolution, Instant from, Instant to,
                               Instant completeBefore) {
        Duration size = resolution.chunk();
        List<Instant> starts = new ArrayList<>();
        for (Instant s = Resolution.floor(from, size); s.isBefore(to); s = s.plus(size)) {
            starts.add(s);
        }
        List<String> keys = starts.stream()
                .map(s -> cache.key("series:{" + deviceId + "|" + metric + "}:" + resolution.id() + ":" + s.getEpochSecond()))
                .toList();
        List<String> cached = cache.getAll(keys);
        Chunk[] chunks = new Chunk[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            if (cached.get(i) != null) {
                chunks[i] = json.readValue(cached.get(i), Chunk.class);
            }
        }
        Map<String, HistoryCache.Entry> toCache = new LinkedHashMap<>();
        int i = 0;
        while (i < starts.size()) {
            if (chunks[i] != null) {
                i++;
                continue;
            }
            int first = i;
            while (i < starts.size() && chunks[i] == null) {
                i++;
            }
            load(deviceId, metric, resolution, starts, first, i, chunks);
            for (int c = first; c < i; c++) {
                Instant end = starts.get(c).plus(size);
                Duration ttl = !end.isAfter(completeBefore) ? properties.completeChunkTtl() : properties.openChunkTtl();
                toCache.put(keys.get(c), new HistoryCache.Entry(json.writeValueAsString(chunks[c]), ttl));
            }
        }
        cache.putAll(toCache);
        return List.of(chunks);
    }

    /** Reads chunks {@code [first, end)} in one query and splits the rows into their chunks. */
    private void load(String deviceId, String metric, Resolution resolution, List<Instant> starts, int first, int end,
                      Chunk[] chunks) {
        Duration size = resolution.chunk();
        for (int c = first; c < end; c++) {
            chunks[c] = resolution == Resolution.RAW ? Chunk.raw() : Chunk.buckets();
        }
        Instant rangeStart = starts.get(first);
        HistoryRepository.PointSink sink = new HistoryRepository.PointSink() {
            @Override
            public void raw(Instant at, double value) {
                chunkOf(at).addRaw(at, value);
            }

            @Override
            public void bucket(Instant at, double min, double avg, double max, long count) {
                chunkOf(at).addBucket(at, min, avg, max, count);
            }

            private Chunk chunkOf(Instant at) {
                return chunks[first + (int) (Duration.between(rangeStart, at).toMillis() / size.toMillis())];
            }
        };
        Instant rangeEnd = starts.get(end - 1).plus(size);
        if (resolution == Resolution.RAW) {
            repository.raw(deviceId, metric, rangeStart, rangeEnd, sink);
        } else {
            repository.buckets(deviceId, metric, resolution.bucket().toSeconds(), rangeStart, rangeEnd, sink);
        }
    }

    private <T> T cached(String suffix, Duration ttl, TypeReference<T> type, java.util.function.Supplier<T> loader) {
        String key = cache.key(suffix);
        String hit = cache.getAll(List.of(key)).getFirst();
        if (hit != null) {
            return json.readValue(hit, type);
        }
        T value = loader.get();
        cache.putAll(Map.of(key, new HistoryCache.Entry(json.writeValueAsString(value), ttl)));
        return value;
    }

    private void requireDevice(String deviceId) {
        if (!repository.deviceExists(deviceId)) {
            throw ApiException.notFound("Device '" + deviceId + "' does not exist.");
        }
    }

    private void validateRange(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw ApiException.invalid(Map.of("from", List.of("must be before to")));
        }
        if (Duration.between(from, to).compareTo(properties.maxRange()) > 0) {
            throw ApiException.invalid(Map.of("to", List.of("the range must not exceed " + properties.maxRange().toDays() + " days")));
        }
    }
}
