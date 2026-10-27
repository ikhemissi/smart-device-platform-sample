package io.github.ikhemissi.sdp.decoder.eventhubs;

import com.azure.messaging.eventhubs.models.ErrorContext;
import com.azure.messaging.eventhubs.models.EventBatchContext;
import io.github.ikhemissi.sdp.decoder.ingest.IngestionService;
import io.github.ikhemissi.sdp.decoder.ingest.TelemetryMessage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event processor callbacks: ingest the batch, and only then checkpoint its last event. If ingestion is aborted
 * (shutdown) the exception propagates, nothing is checkpointed and the batch is redelivered from the last
 * checkpoint.
 */
public class EventBatchHandler {

    private static final Logger log = LoggerFactory.getLogger(EventBatchHandler.class);

    private final IngestionService ingestion;

    public EventBatchHandler(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    public void onBatch(EventBatchContext context) {
        if (context.getEvents().isEmpty()) {
            return;
        }
        String partitionId = context.getPartitionContext().getPartitionId();
        List<TelemetryMessage> messages = context.getEvents().stream()
                .map(event -> EventMapper.map(event, partitionId))
                .toList();
        ingestion.ingest(partitionId, messages);
        context.updateCheckpoint();
    }

    public void onError(ErrorContext context) {
        log.warn("Event processor error on partition {}: {}",
                context.getPartitionContext().getPartitionId(), context.getThrowable().toString());
    }
}
