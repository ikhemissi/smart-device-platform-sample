package io.github.ikhemissi.sdp.decoder.eventhubs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.messaging.eventhubs.CheckpointStore;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.ReceivedEvents;
import com.azure.messaging.eventhubs.models.Checkpoint;
import com.azure.messaging.eventhubs.models.EventBatchContext;
import com.azure.messaging.eventhubs.models.InitializationContext;
import com.azure.messaging.eventhubs.models.LastEnqueuedEventProperties;
import com.azure.messaging.eventhubs.models.PartitionContext;
import com.azure.messaging.eventhubs.models.PartitionOwnership;
import io.github.ikhemissi.sdp.decoder.ingest.IngestionService;
import io.github.ikhemissi.sdp.decoder.ingest.IngestionTelemetry;
import io.github.ikhemissi.sdp.decoder.ingest.PersistResult;
import io.github.ikhemissi.sdp.decoder.ingest.TelemetryStore.StoreResult;
import io.github.ikhemissi.sdp.decoder.ingest.TelemetryStore;
import io.github.ikhemissi.sdp.decoder.ingest.WatermarkStore;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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

    private final Map<String, Instant> watermarks = new HashMap<>();
    private final List<String> registered = new ArrayList<>();
    private final WatermarkStore watermarkStore = new WatermarkStore() {
        @Override
        public void register(String partitionId) {
            registered.add(partitionId);
        }

        @Override
        public void advance(String partitionId, Instant watermark) {
            order.add("watermark");
            watermarks.merge(partitionId, watermark, (a, b) -> a.isAfter(b) ? a : b);
        }
    };
    private static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");
    private static final Duration MAX_WAIT = Duration.ofSeconds(5);

    private EventBatchHandler handler(IngestionService ingestion) {
        return new EventBatchHandler(ingestion, watermarkStore, Clock.fixed(NOW, java.time.ZoneOffset.UTC), MAX_WAIT);
    }

    private EventBatchContext context(int events) {
        return context(events, null);
    }

    /** Events with sequence numbers 0..n-1, enqueued one second apart ending at NOW - 10 s. */
    private EventBatchContext context(int events, LastEnqueuedEventProperties partition) {
        List<EventData> data = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            EventData sent = EventMapperTest.iotHubEvent("dev-" + i, "paris-01", Instant.now(), "m-" + i,
                    HexFormat.of().parseHex("0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F"));
            sent.getRawAmqpMessage().getMessageAnnotations().put("x-opt-sequence-number", (long) i);
            sent.getRawAmqpMessage().getMessageAnnotations().put("x-opt-offset", String.valueOf(i * 100));
            EventData e = ReceivedEvents.received(sent, i, NOW.minusSeconds(10 + events - 1 - i));
            data.add(e);
        }
        return new EventBatchContext(new PartitionContext("ns", "device-telemetry", "telemetry-decoder", "0"),
                data, checkpointStore, partition);
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

        handler(ingestion(store)).onBatch(context(3));

        assertThat(order).containsExactly("persist", "checkpoint", "watermark");
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

        assertThatThrownBy(() -> handler(holder[0]).onBatch(context(3)))
                .isInstanceOf(IngestionService.IngestionAbortedException.class);
        assertThat(checkpoints).isEmpty();
        assertThat(watermarks).isEmpty();
    }

    private TelemetryStore acceptingStore() {
        return batch -> new StoreResult(new PersistResult(batch.measurements().size(), 0, 0), Set.of());
    }

    @Test
    void caughtUpPartitionWatermarkIsWhenItWasLastObserved() {
        Instant observed = NOW.minusSeconds(2);
        handler(ingestion(acceptingStore())).onBatch(context(3, new LastEnqueuedEventProperties(2L, 200L, NOW.minusSeconds(10), observed)));

        assertThat(watermarks).containsEntry("0", observed);
    }

    @Test
    void watermarkStallsBehindABacklog() {
        handler(ingestion(acceptingStore())).onBatch(context(3, new LastEnqueuedEventProperties(5_000L, 500_000L, NOW, NOW)));

        assertThat(watermarks).as("enqueued time of the last persisted event").containsEntry("0", NOW.minusSeconds(10));
    }

    @Test
    void idlePartitionWatermarkLeavesRoomForDeliveryLatency() {
        handler(ingestion(acceptingStore())).onBatch(context(0));

        assertThat(watermarks).containsEntry("0", NOW.minus(MAX_WAIT.multipliedBy(2)));
        assertThat(checkpoints).isEmpty();
    }

    @Test
    void watermarkFailureDoesNotFailTheBatch() {
        EventBatchHandler handler = new EventBatchHandler(ingestion(acceptingStore()), new WatermarkStore() {
            @Override
            public void register(String partitionId) {
                throw new IllegalStateException("db down");
            }

            @Override
            public void advance(String partitionId, Instant watermark) {
                throw new IllegalStateException("db down");
            }
        }, Clock.fixed(NOW, java.time.ZoneOffset.UTC), MAX_WAIT);

        handler.onBatch(context(2));
        handler.onInitialization(new InitializationContext(new PartitionContext("ns", "device-telemetry", "telemetry-decoder", "1")));

        assertThat(checkpoints).hasSize(1);
    }

    @Test
    void partitionsAreRegisteredWhenProcessingStarts() {
        handler(ingestion(acceptingStore())).onInitialization(
                new InitializationContext(new PartitionContext("ns", "device-telemetry", "telemetry-decoder", "3")));

        assertThat(registered).containsExactly("3");
    }

    @Test
    void emptyBatchesPersistNothingButAdvanceTheIdleWatermark() {
        handler(ingestion(batch -> {
            throw new AssertionError("must not persist");
        })).onBatch(context(0));

        assertThat(checkpoints).isEmpty();
        assertThat(watermarks).containsOnlyKeys("0");
    }
}
