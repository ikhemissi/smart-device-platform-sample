package io.github.ikhemissi.sdp.deviceapi.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.ikhemissi.sdp.deviceapi.cache.HistoryCache;
import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Chunked series caching (M3 design D2): alignment, stitching, reuse, watermark-based lifetimes and freshness. */
class HistoryServiceTest {

    private static final String DEVICE = "paris-01-ruuvi5-001";
    private static final Instant T = Instant.parse("2026-10-28T10:00:00Z");

    /** Clock that tests move forward. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** In-memory cache honouring time to live. */
    static final class FakeCache implements HistoryCache {
        final Map<String, Entry> entries = new HashMap<>();
        final Map<String, Instant> expiries = new HashMap<>();
        final MutableClock clock;

        FakeCache(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public List<String> getAll(List<String> keys) {
            List<String> values = new ArrayList<>();
            for (String key : keys) {
                boolean live = entries.containsKey(key) && clock.instant().isBefore(expiries.get(key));
                values.add(live ? entries.get(key).value() : null);
            }
            return values;
        }

        @Override
        public void putAll(Map<String, Entry> values) {
            values.forEach((key, entry) -> {
                entries.put(key, entry);
                expiries.put(key, clock.instant().plus(entry.ttl()));
            });
        }

        @Override
        public String key(String suffix) {
            return suffix;
        }

        Duration ttlOfChunkStartingAt(Instant start) {
            return entries.entrySet().stream().filter(e -> e.getKey().endsWith(":" + start.getEpochSecond()))
                    .map(e -> e.getValue().ttl()).findFirst().orElseThrow();
        }
    }

    private final MutableClock clock = new MutableClock(T.plus(Duration.ofHours(4)));
    private final FakeCache cache = new FakeCache(clock);
    private final HistoryRepository repository = mock(HistoryRepository.class);
    private final IngestionWatermark watermark = mock(IngestionWatermark.class);
    /** Stored raw temperatures by measurement time. */
    private final TreeMap<Instant, Double> stored = new TreeMap<>();
    private HistoryService service;

    @BeforeEach
    void setUp() {
        HistoryProperties properties = new HistoryProperties(Duration.ofDays(30), Duration.ofDays(31), 4,
                Duration.ofMinutes(2), Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMinutes(5));
        service = new HistoryService(repository, cache, watermark, properties, JsonMapper.builder().build(), clock);
        when(repository.deviceExists(DEVICE)).thenReturn(true);
        when(repository.units(anyList())).thenReturn(Map.of("temperature", "°C"));
        when(watermark.completeBefore()).thenReturn(Instant.MIN);
        when(repository.countUpTo(anyString(), any(), any(), anyInt()))
                .thenAnswer(i -> (long) stored.subMap(i.getArgument(1), i.getArgument(2)).size());
        doAnswer(i -> {
            HistoryRepository.PointSink sink = i.getArgument(4);
            stored.subMap(i.getArgument(2), i.getArgument(3)).forEach(sink::raw);
            return null;
        }).when(repository).raw(anyString(), anyString(), any(), any(), any());
        for (Instant t = T; t.isBefore(T.plus(Duration.ofHours(4))); t = t.plusSeconds(60)) {
            stored.put(t, 20 + (t.getEpochSecond() % 600) / 100.0);
        }
    }

    private Series rawSeries(Instant from, Instant to) {
        return service.series(DEVICE, List.of("temperature"), from, to, 2000);
    }

    @Test
    void chunksAreReadInOneRangeAndTrimmedToTheRequest() {
        Instant from = T.plus(Duration.ofMinutes(20));
        Instant to = T.plus(Duration.ofMinutes(190));

        Series series = rawSeries(from, to);

        verify(repository).raw(eq(DEVICE), eq("temperature"), eq(T), eq(T.plus(Duration.ofHours(4))), anySink());
        List<Series.Point> points = series.series().getFirst().points();
        assertThat(series.resolution()).isEqualTo("raw");
        assertThat(series.series().getFirst().unit()).isEqualTo("°C");
        assertThat(points).hasSize(170);
        assertThat(points.getFirst().t()).isEqualTo(from);
        assertThat(points.getLast().t()).isEqualTo(to.minusSeconds(60));
        assertThat(points).extracting(Series.Point::value).containsExactlyElementsOf(stored.subMap(from, to).values());
    }

    @Test
    void cachedChunksAreReused() {
        rawSeries(T.plus(Duration.ofMinutes(20)), T.plus(Duration.ofMinutes(190)));
        clock.now = clock.now.plusSeconds(5);

        Series again = rawSeries(T.plus(Duration.ofMinutes(25)), T.plus(Duration.ofMinutes(195)));

        verify(repository, times(1)).raw(anyString(), anyString(), any(), any(), any());
        assertThat(again.series().getFirst().points()).hasSize(170);
    }

    @Test
    void onlyMissingChunksAreReadFromTheDatabase() {
        rawSeries(T.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(2)));

        rawSeries(T, T.plus(Duration.ofHours(3)));

        verify(repository).raw(eq(DEVICE), eq("temperature"), eq(T.plus(Duration.ofHours(1))), eq(T.plus(Duration.ofHours(2))), anySink());
        verify(repository).raw(eq(DEVICE), eq("temperature"), eq(T), eq(T.plus(Duration.ofHours(1))), anySink());
        verify(repository).raw(eq(DEVICE), eq("temperature"), eq(T.plus(Duration.ofHours(2))), eq(T.plus(Duration.ofHours(3))), anySink());
    }

    @Test
    void chunksEndingBeforeTheWatermarkAreKeptLongerThanOpenOnes() {
        when(watermark.completeBefore()).thenReturn(T.plus(Duration.ofMinutes(150)));

        rawSeries(T, T.plus(Duration.ofHours(4)));

        assertThat(cache.ttlOfChunkStartingAt(T)).isEqualTo(Duration.ofHours(24));
        assertThat(cache.ttlOfChunkStartingAt(T.plus(Duration.ofHours(1)))).isEqualTo(Duration.ofHours(24));
        assertThat(cache.ttlOfChunkStartingAt(T.plus(Duration.ofHours(2)))).as("contains the watermark").isEqualTo(Duration.ofSeconds(30));
        assertThat(cache.ttlOfChunkStartingAt(T.plus(Duration.ofHours(3)))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void lateMeasurementDuringADecoderBacklogIsVisibleWithinAMinute() {
        // The decoder is behind: its watermark has stalled two hours ago.
        when(watermark.completeBefore()).thenReturn(T.plus(Duration.ofHours(2)));
        Instant late = T.plus(Duration.ofMinutes(150)).plusSeconds(30);
        rawSeries(T, T.plus(Duration.ofHours(4)));

        stored.put(late, 99.0);
        clock.now = clock.now.plusSeconds(31);
        Series afterPersistence = rawSeries(T, T.plus(Duration.ofHours(4)));

        assertThat(afterPersistence.series().getFirst().points()).extracting(Series.Point::value).contains(99.0);
    }

    @Test
    void longRangesAreBucketedWithTheSmallestSufficientSize() {
        assertThat(Resolution.bucketsFor(T, T.plus(Duration.ofDays(30)), 500)).isEqualTo(Resolution.HOUR_6);
        assertThat(Resolution.bucketsFor(T, T.plus(Duration.ofDays(1)), 500)).isEqualTo(Resolution.MIN_5);
        assertThat(Resolution.bucketsFor(T, T.plus(Duration.ofDays(7)), 2000)).isEqualTo(Resolution.MIN_15);
        assertThat(Resolution.bucketsFor(T, T.plus(Duration.ofDays(30)), 10)).as("largest available").isEqualTo(Resolution.DAY_1);
    }

    @Test
    void bucketedSeriesUsesDailyChunksAlignedToTheBucketGrid() {
        org.mockito.Mockito.doReturn(501L).when(repository).countUpTo(anyString(), any(), any(), anyInt());
        Instant from = Instant.parse("2026-10-01T07:30:00Z");
        Instant to = Instant.parse("2026-10-28T07:30:00Z");

        Series series = service.series(DEVICE, List.of("temperature"), from, to, 500);

        assertThat(series.resolution()).isEqualTo("6h");
        assertThat(series.bucketSeconds()).isEqualTo(21_600);
        verify(repository).buckets(eq(DEVICE), eq("temperature"), eq(21_600L),
                eq(Instant.parse("2026-10-01T00:00:00Z")), eq(Instant.parse("2026-10-29T00:00:00Z")), anySink());
    }

    @Test
    void rangeIsValidated() {
        assertThatThrownBy(() -> rawSeries(T, T)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rawSeries(T, T.plus(Duration.ofDays(40))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("31 days"));
        verify(repository, never()).raw(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void unknownDeviceIsNotFound() {
        assertThatThrownBy(() -> service.latest("nope", 20))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }

    @Test
    void cursorsRoundTrip() {
        Cursor cursor = new Cursor(Instant.parse("2026-10-28T10:00:00.123456Z"), "m-1|x");

        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor!")).isInstanceOf(ApiException.class);
    }

    private static HistoryRepository.PointSink anySink() {
        return any(HistoryRepository.PointSink.class);
    }

}
