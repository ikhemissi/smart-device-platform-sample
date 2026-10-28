package io.github.ikhemissi.sdp.deviceapi.history;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Time before which stored history can no longer change (M3 design D2): the lowest ingestion watermark over all
 * Event Hubs partitions minus a margin, or {@link Instant#MIN} until every partition has reported. Read at most every
 * few seconds.
 */
@Component
public class IngestionWatermark {

    private static final Logger log = LoggerFactory.getLogger(IngestionWatermark.class);
    private static final Duration REFRESH = Duration.ofSeconds(5);

    private final HistoryRepository repository;
    private final HistoryProperties properties;
    private final Clock clock;
    private volatile Instant value = Instant.MIN;
    private volatile Instant readAt = Instant.MIN;

    public IngestionWatermark(HistoryRepository repository, HistoryProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public Instant completeBefore() {
        Instant now = clock.instant();
        if (now.isAfter(readAt.plus(REFRESH))) {
            try {
                HistoryRepository.Watermarks watermarks = repository.watermarks();
                value = watermarks.partitions() < properties.partitions() || watermarks.lowest() == null
                        || watermarks.lowest().equals(Instant.MIN)
                        ? Instant.MIN
                        : min(watermarks.lowest(), now).minus(properties.watermarkMargin());
            } catch (RuntimeException e) {
                log.warn("Ingestion watermark unavailable, caching nothing as complete: {}", e.toString());
                value = Instant.MIN;
            }
            readAt = now;
        }
        return value;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
