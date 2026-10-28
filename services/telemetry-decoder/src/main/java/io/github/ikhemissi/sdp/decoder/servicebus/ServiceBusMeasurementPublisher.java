package io.github.ikhemissi.sdp.decoder.servicebus;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusMessageBatch;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import io.github.ikhemissi.sdp.decoder.ingest.DecodedBatch.Measurement;
import io.github.ikhemissi.sdp.decoder.ingest.MeasurementPublisher;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes persisted measurements to the Service Bus topic {@code measurements} (ADR-0009). The message is a versioned
 * integration contract:
 * <ul>
 *   <li>message id: SHA-256 (hex) of {@code deviceId + "\n" + messageKey}, unique across devices, so the topic's
 *   duplicate detection drops re-publications of a redelivered batch;</li>
 *   <li>application properties {@code deviceId}, {@code siteId}, {@code payloadFormat} (subscription filters),
 *   {@code schemaVersion}, {@code messageKey}, and {@code traceparent} when traced;</li>
 *   <li>JSON body: schemaVersion, deviceId, siteId, payloadFormat, measuredAt, receivedAt, messageKey, metrics.</li>
 * </ul>
 */
public class ServiceBusMeasurementPublisher implements MeasurementPublisher {

    public static final int SCHEMA_VERSION = 1;
    static final String CONTENT_TYPE = "application/json";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ServiceBusSenderClient sender;

    public ServiceBusMeasurementPublisher(ServiceBusSenderClient sender) {
        this.sender = sender;
    }

    @Override
    public void publish(List<Measurement> measurements) {
        ServiceBusMessageBatch batch = sender.createMessageBatch();
        for (Measurement measurement : measurements) {
            ServiceBusMessage message = toMessage(measurement);
            if (!batch.tryAddMessage(message)) {
                sender.sendMessages(batch);
                batch = sender.createMessageBatch();
                if (!batch.tryAddMessage(message)) {
                    throw new IllegalStateException("Measurement " + message.getMessageId() + " exceeds the maximum message size");
                }
            }
        }
        if (batch.getCount() > 0) {
            sender.sendMessages(batch);
        }
    }

    static ServiceBusMessage toMessage(Measurement m) {
        ObjectNode body = JSON.createObjectNode()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("deviceId", m.deviceId())
                .put("siteId", m.siteId())
                .put("payloadFormat", m.payloadFormat())
                .put("measuredAt", m.measuredAt().toString())
                .put("receivedAt", m.receivedAt().toString())
                .put("messageKey", m.messageKey());
        ObjectNode metrics = body.putObject("metrics");
        m.metrics().forEach(metrics::put);

        ServiceBusMessage message = new ServiceBusMessage(JSON.writeValueAsBytes(body))
                .setMessageId(messageId(m.deviceId(), m.messageKey()))
                .setContentType(CONTENT_TYPE)
                .setSubject("measurement");
        var properties = message.getApplicationProperties();
        properties.put("deviceId", m.deviceId());
        properties.put("siteId", m.siteId());
        properties.put("payloadFormat", m.payloadFormat());
        properties.put("schemaVersion", SCHEMA_VERSION);
        properties.put("messageKey", m.messageKey());
        SpanContext span = Span.current().getSpanContext();
        if (span.isValid()) {
            properties.put("traceparent", "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getTraceFlags().asHex());
        }
        return message;
    }

    /** Unique across devices (device message ids are only unique per device), at most 128 characters. */
    public static String messageId(String deviceId, String messageKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((deviceId + "\n" + messageKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
