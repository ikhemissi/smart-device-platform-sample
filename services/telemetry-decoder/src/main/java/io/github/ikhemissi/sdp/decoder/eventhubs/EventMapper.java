package io.github.ikhemissi.sdp.decoder.eventhubs;

import com.azure.messaging.eventhubs.EventData;
import io.github.ikhemissi.sdp.decoder.ingest.TelemetryMessage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Map;

/**
 * Maps an Event Hubs event routed by IoT Hub to a {@link TelemetryMessage}. IoT Hub system properties travel as
 * AMQP message annotations; the site is an application property added by IoT Hub message enrichment.
 */
public final class EventMapper {

    static final String DEVICE_ID = "iothub-connection-device-id";
    static final String ENQUEUED_TIME = "iothub-enqueuedtime";
    static final String SITE_ID = "siteId";
    static final String TRACEPARENT = "traceparent";
    static final String SEQUENCE_NUMBER = "x-opt-sequence-number";

    private EventMapper() {
    }

    public static TelemetryMessage map(EventData event, String partitionId) {
        Map<String, Object> annotations = event.getRawAmqpMessage().getMessageAnnotations();
        Map<String, Object> properties = event.getProperties();
        return new TelemetryMessage(
                text(annotations.get(DEVICE_ID)),
                text(properties.get(SITE_ID)),
                enqueuedAt(annotations.get(ENQUEUED_TIME), event),
                messageKey(event, annotations, partitionId),
                event.getBody(),
                text(properties.get(TRACEPARENT)));
    }

    private static String messageKey(EventData event, Map<String, Object> annotations, String partitionId) {
        String messageId = event.getMessageId();
        if (messageId != null && !messageId.isBlank()) {
            return messageId;
        }
        Object sequence = event.getSequenceNumber() != null ? event.getSequenceNumber() : annotations.get(SEQUENCE_NUMBER);
        return partitionId + ":" + sequence;
    }

    private static Instant enqueuedAt(Object iotHubEnqueuedTime, EventData event) {
        Instant instant = switch (iotHubEnqueuedTime) {
            case Date d -> d.toInstant();
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case String s when !s.isBlank() -> Instant.parse(s);
            case null, default -> null;
        };
        if (instant == null) {
            instant = event.getEnqueuedTime();
        }
        return instant != null ? instant : Instant.now();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
