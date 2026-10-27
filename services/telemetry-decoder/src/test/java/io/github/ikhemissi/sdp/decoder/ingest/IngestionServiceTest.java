package io.github.ikhemissi.sdp.decoder.ingest;

import static io.github.ikhemissi.sdp.decoder.ingest.BatchDecoderTest.RUUVI_DF5;
import static io.github.ikhemissi.sdp.decoder.ingest.BatchDecoderTest.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ikhemissi.sdp.decoder.ingest.TelemetryStore.StoreResult;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec telemetry-ingestion "Storage unavailable" (retry with back-off, no acknowledgement until stored) and
 * platform-observability "Ingestion metrics" / "End-to-end message correlation" (task 4.5).
 */
class IngestionServiceTest {

    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build();
    private final List<Duration> sleeps = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T09:00:05Z"), ZoneOffset.UTC);

    @AfterEach
    void close() {
        otel.close();
    }

    private IngestionService service(TelemetryStore store) {
        return new IngestionService(store, new IngestionTelemetry(otel), Duration.ofSeconds(1), Duration.ofSeconds(4),
                sleeps::add, clock);
    }

    @Test
    void retriesWithExponentialBackoffUntilPersistedAndReportsHealth() {
        AtomicInteger calls = new AtomicInteger();
        List<IngestionService.Health> healthDuringRetries = new ArrayList<>();
        IngestionService[] holder = new IngestionService[1];
        TelemetryStore store = batch -> {
            if (calls.incrementAndGet() <= 4) {
                if (holder[0] != null) {
                    healthDuringRetries.add(holder[0].health());
                }
                throw new IllegalStateException("database unavailable");
            }
            return new StoreResult(new PersistResult(batch.measurements().size(), 0, 0), Set.of());
        };
        IngestionService service = service(store);
        holder[0] = service;

        PersistResult result = service.ingest("0", List.of(message("dev", "paris-01", "k", RUUVI_DF5)));

        assertThat(result.storedMeasurements()).isEqualTo(1);
        assertThat(calls).hasValue(5);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(4));
        assertThat(healthDuringRetries.subList(1, healthDuringRetries.size()))
                .allSatisfy(h -> assertThat(h.healthy()).isFalse());
        assertThat(service.health().healthy()).isTrue();
    }

    @Test
    void stoppingAbortsAnUnpersistedBatch() {
        IngestionService[] holder = new IngestionService[1];
        TelemetryStore store = batch -> {
            holder[0].stop();
            throw new IllegalStateException("database unavailable");
        };
        holder[0] = service(store);

        assertThatThrownBy(() -> holder[0].ingest("0", List.of(message("dev", "paris-01", "k", RUUVI_DF5))))
                .isInstanceOf(IngestionService.IngestionAbortedException.class);
        assertThat(holder[0].health().healthy()).isFalse();
    }

    @Test
    void emitsMetricsAndLinkedSpansPerMessage() {
        TelemetryMessage traced = new TelemetryMessage("paris-01-ruuvi5-001", "paris-01",
                Instant.parse("2026-10-27T09:00:00Z"), "k1", BatchDecoderTest.message("x", "y", "z", RUUVI_DF5).payload(), TRACEPARENT);
        TelemetryMessage corrupted = message("paris-01-ruuvi5-002", "paris-01", "k2", "7F01");
        TelemetryMessage duplicate = message("paris-01-ruuvi5-003", "paris-01", "k3", RUUVI_DF5);
        TelemetryStore store = batch -> new StoreResult(new PersistResult(1, 1, 1),
                Set.of(TelemetryStore.key("paris-01-ruuvi5-003", "k3")));

        service(store).ingest("2", List.of(traced, corrupted, duplicate));

        List<SpanData> finished = spans.getFinishedSpanItems();
        SpanData batchSpan = finished.stream().filter(s -> s.getName().equals("sdp.ingest.batch")).findFirst().orElseThrow();
        assertThat(batchSpan.getAttributes().get(IngestionTelemetry.PARTITION)).isEqualTo("2");
        List<SpanData> messageSpans = finished.stream().filter(s -> s.getName().equals("sdp.ingest.message")).toList();
        assertThat(messageSpans).hasSize(3).allSatisfy(s -> assertThat(s.getParentSpanId()).isEqualTo(batchSpan.getSpanId()));

        SpanData tracedSpan = messageSpans.stream()
                .filter(s -> "paris-01-ruuvi5-001".equals(s.getAttributes().get(IngestionTelemetry.DEVICE_ID))).findFirst().orElseThrow();
        assertThat(tracedSpan.getAttributes().get(IngestionTelemetry.SITE_ID)).isEqualTo("paris-01");
        assertThat(tracedSpan.getAttributes().get(IngestionTelemetry.PAYLOAD_FORMAT)).isEqualTo("ruuvi-df5");
        assertThat(tracedSpan.getAttributes().get(IngestionTelemetry.OUTCOME)).isEqualTo("stored");
        assertThat(tracedSpan.getLinks()).singleElement()
                .satisfies(l -> assertThat(l.getSpanContext().getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c"));

        assertThat(counter(IngestionTelemetry.OUTCOME, "rejected", "UNKNOWN_FORMAT")).isEqualTo(1);
        assertThat(counter(IngestionTelemetry.OUTCOME, "stored", null)).isEqualTo(1);
        assertThat(counter(IngestionTelemetry.OUTCOME, "duplicate", null)).isEqualTo(1);
        MetricData latency = metric("sdp.ingestion.latency");
        assertThat(latency.getHistogramData().getPoints()).singleElement()
                .satisfies(p -> assertThat(p.getSum()).isEqualTo(5.0));
        assertThat(metric("sdp.ingestion.batch.size").getHistogramData().getPoints()).singleElement()
                .satisfies(p -> assertThat(p.getSum()).isEqualTo(3.0));
    }

    private long counter(AttributeKey<String> key, String outcome, String reason) {
        return metric("sdp.ingestion.messages").getLongSumData().getPoints().stream()
                .filter(p -> outcome.equals(p.getAttributes().get(key)))
                .filter(p -> reason == null || reason.equals(p.getAttributes().get(IngestionTelemetry.REASON)))
                .mapToLong(p -> p.getValue()).sum();
    }

    private MetricData metric(String name) {
        return metrics.collectAllMetrics().stream().filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }
}
