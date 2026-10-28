package io.github.ikhemissi.sdp.alerts.measurements;

import com.azure.core.credential.TokenCredential;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import io.github.ikhemissi.sdp.alerts.AlertingProperties;
import io.github.ikhemissi.sdp.alerts.evaluation.AlertEvaluator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Consumes the {@code alerting} subscription (peek-lock): each measurement is evaluated, then the message is completed.
 * A message that can never be processed is dead-lettered; on any other failure it is abandoned and redelivered (at
 * most 10 times, then dead-lettered by Service Bus). The decoder's trace context ({@code traceparent}) is continued.
 */
@Component
@ConditionalOnProperty(name = "sdp.alerting.servicebus.enabled", havingValue = "true", matchIfMissing = true)
public class MeasurementProcessor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MeasurementProcessor.class);
    private static final TextMapGetter<Map<String, Object>> PROPERTIES = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, Object> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, Object> carrier, String key) {
            Object value = carrier == null ? null : carrier.get(key);
            return value == null ? null : value.toString();
        }
    };

    private final AlertEvaluator evaluator;
    private final Tracer tracer;
    private final ServiceBusProcessorClient processor;
    private volatile boolean running;

    public MeasurementProcessor(AlertEvaluator evaluator, AlertingProperties properties, TokenCredential credential,
                                OpenTelemetry openTelemetry) {
        this.evaluator = evaluator;
        this.tracer = openTelemetry.getTracer("sdp-alert-service");
        AlertingProperties.ServiceBus sb = properties.servicebus();
        ServiceBusClientBuilder builder = new ServiceBusClientBuilder();
        if (StringUtils.hasText(sb.connectionString())) {
            builder.connectionString(sb.connectionString());
        } else if (StringUtils.hasText(sb.fullyQualifiedNamespace())) {
            builder.fullyQualifiedNamespace(sb.fullyQualifiedNamespace()).credential(credential);
        } else {
            throw new IllegalStateException("sdp.alerting.servicebus.fully-qualified-namespace is required");
        }
        this.processor = builder.processor()
                .topicName(sb.topic())
                .subscriptionName(sb.subscription())
                .maxConcurrentCalls(sb.maxConcurrentCalls())
                .prefetchCount(sb.prefetch())
                .disableAutoComplete()
                .processMessage(this::process)
                .processError(this::error)
                .buildProcessorClient();
    }

    void process(ServiceBusReceivedMessageContext context) {
        ServiceBusReceivedMessage message = context.getMessage();
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), message.getApplicationProperties(), PROPERTIES);
        Span span = tracer.spanBuilder("sdp.alert.evaluate").setParent(parent).setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.message.id", message.getMessageId())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Measurement measurement = Measurement.parse(message.getBody().toBytes());
            span.setAttribute("sdp.device.id", measurement.deviceId());
            List<AlertEvaluator.Evaluation> evaluations = evaluator.evaluate(measurement);
            evaluations.forEach(e -> log.debug("Device {} threshold {}: {}", measurement.deviceId(), e.thresholdId(), e.outcome()));
            context.complete();
        } catch (Measurement.InvalidMeasurementException e) {
            log.warn("Dead-lettering message {}: {}", message.getMessageId(), e.getMessage());
            span.setStatus(StatusCode.ERROR, "invalid measurement");
            context.deadLetter(new DeadLetterOptions().setDeadLetterReason("InvalidMeasurement").setDeadLetterErrorDescription(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Evaluating message {} failed (redelivered): {}", message.getMessageId(), e.toString());
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            context.abandon();
        } finally {
            span.end();
        }
    }

    private void error(ServiceBusErrorContext context) {
        log.warn("Service Bus processor error ({}): {}", context.getErrorSource(), context.getException().toString());
    }

    @Override
    public void start() {
        processor.start();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        processor.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
