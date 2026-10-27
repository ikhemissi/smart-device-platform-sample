package io.github.ikhemissi.sdp.decoder.eventhubs;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.amqp.models.AmqpMessageId;
import com.azure.messaging.eventhubs.EventData;
import io.github.ikhemissi.sdp.decoder.ingest.TelemetryMessage;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

class EventMapperTest {

    static EventData iotHubEvent(String deviceId, String siteId, Instant enqueued, String messageId, byte[] body) {
        EventData event = new EventData(body);
        if (deviceId != null) {
            event.getRawAmqpMessage().getMessageAnnotations().put(EventMapper.DEVICE_ID, deviceId);
        }
        if (enqueued != null) {
            event.getRawAmqpMessage().getMessageAnnotations().put(EventMapper.ENQUEUED_TIME, Date.from(enqueued));
        }
        if (messageId != null) {
            event.getRawAmqpMessage().getProperties().setMessageId(new AmqpMessageId(messageId));
        }
        if (siteId != null) {
            event.getProperties().put(EventMapper.SITE_ID, siteId);
        }
        return event;
    }

    @Test
    void mapsIotHubPropertiesAndEnrichment() {
        Instant enqueued = Instant.parse("2026-10-27T09:00:00Z");
        EventData event = iotHubEvent("paris-01-ruuvi5-001", "paris-01", enqueued, "msg-1", new byte[] {5, 1});
        event.getProperties().put(EventMapper.TRACEPARENT, "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        TelemetryMessage m = EventMapper.map(event, "0");

        assertThat(m.deviceId()).isEqualTo("paris-01-ruuvi5-001");
        assertThat(m.siteId()).isEqualTo("paris-01");
        assertThat(m.enqueuedAt()).isEqualTo(enqueued);
        assertThat(m.messageKey()).isEqualTo("msg-1");
        assertThat(m.payload()).containsExactly(5, 1);
        assertThat(m.traceparent()).startsWith("00-0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void messageKeyFallsBackToPartitionAndSequenceNumber() {
        EventData event = iotHubEvent("dev", "site-a", Instant.now(), null, new byte[] {5});
        event.getRawAmqpMessage().getMessageAnnotations().put(EventMapper.SEQUENCE_NUMBER, 42L);

        assertThat(EventMapper.map(event, "3").messageKey()).isEqualTo("3:42");
    }

    @Test
    void missingDeviceAndSiteAreNull() {
        TelemetryMessage m = EventMapper.map(iotHubEvent(null, "  ", Instant.now(), "k", new byte[] {5}), "0");

        assertThat(m.deviceId()).isNull();
        assertThat(m.siteId()).isNull();
    }
}
