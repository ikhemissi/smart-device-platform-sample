package io.github.ikhemissi.sdp.decoder.eventhubs;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.models.ErrorContext;
import com.azure.messaging.eventhubs.models.EventBatchContext;
import com.azure.messaging.eventhubs.models.InitializationContext;
import com.azure.messaging.eventhubs.models.LastEnqueuedEventProperties;
import io.github.ikhemissi.sdp.decoder.ingest.IngestionService;
import io.github.ikhemissi.sdp.decoder.ingest.TelemetryMessage;
import io.github.ikhemissi.sdp.decoder.ingest.WatermarkStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event processor callbacks: ingest the batch, and only then checkpoint its last event. If ingestion is aborted
 * (shutdown) the exception propagates, nothing is checkpointed and the batch is redelivered from the last
 * checkpoint. After each checkpoint the partition's ingestion watermark moves forward.
 */
public class EventBatchHandler {

    private static final Logger log = LoggerFactory.getLogger(EventBatchHandler.class);

    private final IngestionService ingestion;
    private final WatermarkStore watermarks;
    private final Clock clock;
    private final Duration maxWait;

    public EventBatchHandler(IngestionService ingestion, WatermarkStore watermarks, Clock clock, Duration maxWait) {
        this.ingestion = ingestion;
        this.watermarks = watermarks;
        this.clock = clock;
        this.maxWait = maxWait;
    }

    public void onBatch(EventBatchContext context) {
        String partitionId = context.getPartitionContext().getPartitionId();
        if (!context.getEvents().isEmpty()) {
            List<TelemetryMessage> messages = context.getEvents().stream()
                    .map(event -> EventMapper.map(event, partitionId))
                    .toList();
            ingestion.ingest(partitionId, messages);
            context.updateCheckpoint();
        }
        Instant watermark = watermark(context, clock.instant(), maxWait);
        if (!watermark.equals(Instant.MIN)) {
            advanceWatermark(partitionId, watermark);
        }
    }

    public void onInitialization(InitializationContext context) {
        String partitionId = context.getPartitionContext().getPartitionId();
        try {
            watermarks.register(partitionId);
        } catch (RuntimeException e) {
            log.warn("Could not register the ingestion watermark of partition {}: {}", partitionId, e.toString());
        }
    }

    public void onError(ErrorContext context) {
        log.warn("Event processor error on partition {}: {}",
                context.getPartitionContext().getPartitionId(), context.getThrowable().toString());
    }

    /**
     * Time before which every message of the partition is persisted, once the batch is checkpointed:
     * <ul>
     *   <li>caught up (the batch ends with the last event enqueued when the partition was last observed): the time of
     *   that observation;</li>
     *   <li>backlog: the enqueued time of the batch's last event, so the watermark stalls behind unprocessed events;</li>
     *   <li>empty batch (no event for a whole wait window): now minus two windows, leaving room for delivery
     *   latency.</li>
     * </ul>
     */
    static Instant watermark(EventBatchContext context, Instant now, Duration maxWait) {
        List<EventData> events = context.getEvents();
        if (events.isEmpty()) {
            return now.minus(maxWait.multipliedBy(2));
        }
        EventData last = events.getLast();
        LastEnqueuedEventProperties partition = context.getLastEnqueuedEventProperties();
        if (partition != null && partition.getSequenceNumber() != null && partition.getRetrievalTime() != null
                && last.getSequenceNumber() != null && last.getSequenceNumber() >= partition.getSequenceNumber()) {
            return partition.getRetrievalTime().isBefore(now) ? partition.getRetrievalTime() : now;
        }
        // Unknown position: no progress claimed.
        return last.getEnqueuedTime() != null ? last.getEnqueuedTime() : Instant.MIN;
    }

    private void advanceWatermark(String partitionId, Instant watermark) {
        try {
            watermarks.advance(partitionId, watermark);
        } catch (RuntimeException e) {
            // A stale watermark only makes the history cache more conservative.
            log.warn("Could not advance the ingestion watermark of partition {}: {}", partitionId, e.toString());
        }
    }
}
