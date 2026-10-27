package io.github.ikhemissi.sdp.decoder.ingest;

import io.github.ikhemissi.sdp.decoder.ingest.DecodedBatch.Outcome;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ingestion metrics and spans (spec platform-observability; implementation guidelines §5). One
 * {@code sdp.ingest.batch} span per batch and one {@code sdp.ingest.message} child span per message, linked to the
 * trace context propagated by the device so a message can be followed from its send operation to its storage.
 */
public final class IngestionTelemetry {

    public static final AttributeKey<String> DEVICE_ID = AttributeKey.stringKey("sdp.device.id");
    public static final AttributeKey<String> SITE_ID = AttributeKey.stringKey("sdp.site.id");
    public static final AttributeKey<String> PAYLOAD_FORMAT = AttributeKey.stringKey("sdp.payload.format");
    public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("sdp.ingestion.outcome");
    public static final AttributeKey<String> REASON = AttributeKey.stringKey("sdp.rejection.reason");
    public static final AttributeKey<String> PARTITION = AttributeKey.stringKey("messaging.destination.partition.id");
    public static final AttributeKey<Long> BATCH_SIZE = AttributeKey.longKey("sdp.batch.size");

    public static final String STORED = "stored";
    public static final String REJECTED = "rejected";
    public static final String DUPLICATE = "duplicate";

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final Tracer tracer;
    private final LongCounter messages;
    private final LongHistogram batchSize;
    private final DoubleHistogram latency;

    public IngestionTelemetry(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("sdp-telemetry-decoder");
        var meter = openTelemetry.getMeter("sdp-telemetry-decoder");
        this.messages = meter.counterBuilder("sdp.ingestion.messages")
                .setDescription("Device messages processed, by payload format, outcome and rejection reason")
                .setUnit("{message}").build();
        this.batchSize = meter.histogramBuilder("sdp.ingestion.batch.size").ofLongs()
                .setDescription("Number of messages per processed batch").setUnit("{message}").build();
        this.latency = meter.histogramBuilder("sdp.ingestion.latency")
                .setDescription("Time from platform receipt (IoT Hub enqueue) to persistence").setUnit("s").build();
    }

    public Span startBatch(String partitionId, int size) {
        batchSize.record(size);
        return tracer.spanBuilder("sdp.ingest.batch")
                .setAttribute(PARTITION, partitionId == null ? "" : partitionId)
                .setAttribute(BATCH_SIZE, (long) size)
                .startSpan();
    }

    /** Starts one span per message (child of the current batch span, linked to the device's trace context). */
    public List<Span> startMessages(List<Outcome> outcomes) {
        List<Span> spans = new ArrayList<>(outcomes.size());
        for (Outcome o : outcomes) {
            TelemetryMessage m = o.message();
            SpanBuilder builder = tracer.spanBuilder("sdp.ingest.message");
            SpanContext device = deviceContext(m.traceparent());
            if (device.isValid()) {
                builder.addLink(device);
            }
            setIfPresent(builder, DEVICE_ID, m.deviceId());
            setIfPresent(builder, SITE_ID, m.siteId());
            setIfPresent(builder, PAYLOAD_FORMAT, o.payloadFormat());
            spans.add(builder.startSpan());
        }
        return spans;
    }

    /** Records metrics and ends message spans once the batch outcome is known. */
    public void completeMessages(List<Outcome> outcomes, List<Span> spans, Set<String> duplicateKeys, Instant persistedAt) {
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome o = outcomes.get(i);
            TelemetryMessage m = o.message();
            boolean duplicate = duplicateKeys.contains(TelemetryStore.key(m.deviceId(), m.messageKey()));
            String outcome = duplicate ? DUPLICATE : o.rejected() ? REJECTED : STORED;

            var attributes = Attributes.builder()
                    .put(PAYLOAD_FORMAT, o.payloadFormat() == null ? "unknown" : o.payloadFormat())
                    .put(OUTCOME, outcome);
            if (o.rejected()) {
                attributes.put(REASON, o.rejectionReason());
            }
            messages.add(1, attributes.build());
            if (STORED.equals(outcome) && m.enqueuedAt() != null) {
                latency.record(Duration.between(m.enqueuedAt(), persistedAt).toMillis() / 1000.0,
                        Attributes.of(PAYLOAD_FORMAT, o.payloadFormat()));
            }

            Span span = spans.get(i);
            span.setAttribute(OUTCOME, outcome);
            if (o.rejected()) {
                span.setAttribute(REASON, o.rejectionReason());
            }
            span.end();
        }
    }

    public void failMessages(List<Span> spans, Throwable error) {
        for (Span span : spans) {
            span.setStatus(StatusCode.ERROR, error.getClass().getSimpleName());
            span.end();
        }
    }

    private static void setIfPresent(SpanBuilder builder, AttributeKey<String> key, String value) {
        if (value != null) {
            builder.setAttribute(key, value);
        }
    }

    private static SpanContext deviceContext(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return SpanContext.getInvalid();
        }
        Context extracted = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), Map.of("traceparent", traceparent), MAP_GETTER);
        return Span.fromContext(extracted).getSpanContext();
    }
}
