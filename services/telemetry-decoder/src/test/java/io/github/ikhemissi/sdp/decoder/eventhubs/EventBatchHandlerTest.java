package io.github.ikhemissi.sdp.decoder.eventhubs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.messaging.eventhubs.CheckpointStore;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.models.Checkpoint;
import com.azure.messaging.eventhubs.models.EventBatchContext;
import com.azure.messaging.eventhubs.models.PartitionContext;
import com.azure.messaging.eventhubs.models.PartitionOwnership;
import io.github.ikhemissi.sdp.decoder.ingest.IngestionService;
import io.github.ikhemissi.sdp.decoder.ingest.IngestionTelemetry;
import io.github.ikhemissi.sdp.decoder.ingest.PersistResult;
import io.github.ikhemissi.sdp.decoder.ingest.TelemetryStore;
import io.github.ikhemissi.sdp.decoder.ingest.TelemetryStore.StoreResult;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Spec telemetry-ingestion "Batch persisted then acknowledged": the checkpoint follows persistence. */
class EventBatchHandlerTest {

    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private final List<String> order = new ArrayList<>();

    private final CheckpointStore checkpointStore = new CheckpointStore() {
        @Override
        public Flux<PartitionOwnership> listOwnership(String ns, String hub, String cg) {
            return Flux.empty();
        }

        @Override
        public Flux<PartitionOwnership> claimOwnership(List<PartitionOwnership> requested) {
            return Flux.fromIterable(requested);
        }

        @Override
        public Flux<Checkpoint> listCheckpoints(String ns, String hub, String cg) {
            return Flux.fromIterable(checkpoints);
        }

        @Override
        public Mono<Void> updateCheckpoint(Checkpoint checkpoint) {
            return Mono.fromRunnable(() -> {
                order.add("checkpoint");
                checkpoints.add(checkpoint);
            });
        }
    };

    private EventBatchContext context(int events) {
        List<EventData> data = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            EventData e = EventMapperTest.iotHubEvent("dev-" + i, "paris-01", Instant.now(), "m-" + i,
                    HexFormat.of().parseHex("0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F"));
            e.getRawAmqpMessage().getMessageAnnotations().put("x-opt-sequence-number", (long) i);
            e.getRawAmqpMessage().getMessageAnnotations().put("x-opt-offset", String.valueOf(i * 100));
            data.add(e);
        }
        return new EventBatchContext(new PartitionContext("ns", "device-telemetry", "telemetry-decoder", "0"),
                data, checkpointStore, null);
    }

    private IngestionService ingestion(TelemetryStore store) {
        return new IngestionService(store, new IngestionTelemetry(OpenTelemetry.noop()), Duration.ofMillis(1),
                Duration.ofMillis(2), d -> { }, Clock.systemUTC());
    }

    @Test
    void checkpointsTheLastEventAfterPersistence() {
        TelemetryStore store = batch -> {
            order.add("persist");
            return new StoreResult(new PersistResult(batch.measurements().size(), 0, 0), Set.of());
        };

        new EventBatchHandler(ingestion(store)).onBatch(context(3));

        assertThat(order).containsExactly("persist", "checkpoint");
        assertThat(checkpoints).singleElement().satisfies(c -> assertThat(c.getPartitionId()).isEqualTo("0"));
    }

    @Test
    void noCheckpointWhenTheBatchCannotBePersisted() {
        IngestionService[] holder = new IngestionService[1];
        TelemetryStore store = batch -> {
            holder[0].stop();
            throw new IllegalStateException("database unavailable");
        };
        holder[0] = ingestion(store);

        assertThatThrownBy(() -> new EventBatchHandler(holder[0]).onBatch(context(3)))
                .isInstanceOf(IngestionService.IngestionAbortedException.class);
        assertThat(checkpoints).isEmpty();
    }

    @Test
    void emptyBatchesAreIgnored() {
        new EventBatchHandler(ingestion(batch -> {
            throw new AssertionError("must not persist");
        })).onBatch(context(0));

        assertThat(checkpoints).isEmpty();
    }
}
