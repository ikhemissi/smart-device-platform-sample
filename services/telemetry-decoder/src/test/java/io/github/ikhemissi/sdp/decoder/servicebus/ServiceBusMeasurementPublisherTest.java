package io.github.ikhemissi.sdp.decoder.servicebus;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusMessage;
import io.github.ikhemissi.sdp.decoder.ingest.DecodedBatch.Measurement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The published measurement message is a versioned integration contract (ADR-0009). */
class ServiceBusMeasurementPublisherTest {

    private static Measurement measurement(String deviceId, String messageKey) {
        return new Measurement(deviceId, "paris-01", Instant.parse("2026-10-28T10:00:00Z"), Instant.parse("2026-10-28T10:00:02Z"),
                messageKey, "ruuvi-df5", new byte[] {5}, Map.of("temperature", new BigDecimal("12.35"), "humidity", new BigDecimal("40")),
                Map.of());
    }

    @Test
    void carriesFilterablePropertiesAndTheMeasurement() {
        ServiceBusMessage message = ServiceBusMeasurementPublisher.toMessage(measurement("paris-01-ruuvi5-001", "k-1"));

        assertThat(message.getApplicationProperties()).containsEntry("deviceId", "paris-01-ruuvi5-001")
                .containsEntry("siteId", "paris-01").containsEntry("payloadFormat", "ruuvi-df5")
                .containsEntry("schemaVersion", 1).containsEntry("messageKey", "k-1");
        assertThat(message.getContentType()).isEqualTo("application/json");
        JsonNode body = JsonMapper.builder().build().readTree(message.getBody().toBytes());
        assertThat(body.path("measuredAt").asString()).isEqualTo("2026-10-28T10:00:00Z");
        assertThat(body.path("metrics").path("temperature").decimalValue()).isEqualByComparingTo("12.35");
        assertThat(body.path("deviceId").asString()).isEqualTo("paris-01-ruuvi5-001");
    }

    @Test
    void messageIdsAreUniqueAcrossDevicesAndStable() {
        String first = ServiceBusMeasurementPublisher.toMessage(measurement("dev-a", "k-1")).getMessageId();
        String sameKeyOtherDevice = ServiceBusMeasurementPublisher.toMessage(measurement("dev-b", "k-1")).getMessageId();
        String again = ServiceBusMeasurementPublisher.toMessage(measurement("dev-a", "k-1")).getMessageId();

        assertThat(first).hasSize(64).isNotEqualTo(sameKeyOtherDevice).isEqualTo(again);
    }
}
