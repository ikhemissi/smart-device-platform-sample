package io.github.ikhemissi.sdp.decoder.ingest;

import io.github.ikhemissi.sdp.decoder.ingest.TelemetryStore.StoreResult;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes and persists one batch. Persistence is retried with exponential back-off until it succeeds, so the
 * caller only acknowledges (checkpoints) a batch once it is stored (spec telemetry-ingestion: "Batch processing of
 * device messages"). While persistence fails the service reports itself unhealthy.
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** Sleeps between retries; replaceable in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Thrown when the service stops while a batch is still unpersisted; the batch must not be acknowledged. */
    public static final class IngestionAbortedException extends RuntimeException {
        IngestionAbortedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record Health(boolean healthy, Instant failingSince, String lastError) {
    }

    private final TelemetryStore store;
    private final IngestionTelemetry telemetry;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Sleeper sleeper;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<Health> health = new AtomicReference<>(new Health(true, null, null));

    public IngestionService(TelemetryStore store, IngestionTelemetry telemetry, Duration initialBackoff,
                            Duration maxBackoff, Sleeper sleeper, Clock clock) {
        this.store = store;
        this.telemetry = telemetry;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public PersistResult ingest(String partitionId, List<TelemetryMessage> messages) {
        Span batchSpan = telemetry.startBatch(partitionId, messages.size());
        try (Scope ignored = batchSpan.makeCurrent()) {
            DecodedBatch batch = BatchDecoder.decode(messages, clock.instant());
            List<Span> messageSpans = telemetry.startMessages(batch.outcomes());
            batch.rejections().forEach(r -> log.warn("Rejected device message: device={} site={} key={} reason={} detail={}",
                    r.deviceId(), r.siteId(), r.messageKey(), r.reason(), r.detail()));
            try {
                StoreResult result = persistWithRetry(batch);
                telemetry.completeMessages(batch.outcomes(), messageSpans, result.duplicateKeys(), clock.instant());
                log.debug("Batch persisted: partition={} size={} result={}", partitionId, messages.size(), result.counts());
                return result.counts();
            } catch (RuntimeException e) {
                telemetry.failMessages(messageSpans, e);
                throw e;
            }
        } catch (RuntimeException e) {
            batchSpan.recordException(e);
            batchSpan.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            throw e;
        } finally {
            batchSpan.end();
        }
    }

    private StoreResult persistWithRetry(DecodedBatch batch) {
        Duration backoff = initialBackoff;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                StoreResult result = store.persist(batch);
                if (!health.get().healthy()) {
                    log.info("Persistence recovered after {} attempt(s)", attempt);
                }
                health.set(new Health(true, null, null));
                return result;
            } catch (RuntimeException e) {
                Health previous = health.get();
                Instant since = previous.healthy() ? clock.instant() : previous.failingSince();
                health.set(new Health(false, since, e.getClass().getSimpleName() + ": " + e.getMessage()));
                if (!running.get()) {
                    throw new IngestionAbortedException("Stopping with an unpersisted batch; it will be redelivered", e);
                }
                log.warn("Persisting batch failed (attempt {}), retrying in {}: {}", attempt, backoff, e.toString());
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IngestionAbortedException("Interrupted while retrying; batch will be redelivered", e);
                }
                backoff = backoff.multipliedBy(2).compareTo(maxBackoff) > 0 ? maxBackoff : backoff.multipliedBy(2);
            }
        }
    }

    public Health health() {
        return health.get();
    }

    /** Stops retrying: an in-flight unpersisted batch is abandoned (not acknowledged) and will be redelivered. */
    public void stop() {
        running.set(false);
    }
}
