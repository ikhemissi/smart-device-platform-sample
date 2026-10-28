package io.github.ikhemissi.sdp.alerts.measurements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The consumer side of the published measurement contract (ADR-0009, schema version 1). */
class MeasurementTest {

    @Test
    void parsesTheDecodersMessage() {
        Measurement m = Measurement.parse("""
                {"schemaVersion":1,"deviceId":"paris-01-ruuvi5-001","siteId":"paris-01","payloadFormat":"ruuvi-df5",
                 "measuredAt":"2026-10-28T10:00:00Z","receivedAt":"2026-10-28T10:00:02Z","messageKey":"k-1",
                 "metrics":{"temperature":12.35,"humidity":40}}""".getBytes(StandardCharsets.UTF_8));

        assertThat(m.deviceId()).isEqualTo("paris-01-ruuvi5-001");
        assertThat(m.measuredAt()).isEqualTo(Instant.parse("2026-10-28T10:00:00Z"));
        assertThat(m.metrics().get("temperature")).isEqualByComparingTo("12.35");
        assertThat(m.messageKey()).isEqualTo("k-1");
    }

    @Test
    void rejectsMessagesThatCanNeverBeProcessed() {
        assertThatThrownBy(() -> Measurement.parse("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Measurement.InvalidMeasurementException.class);
        assertThatThrownBy(() -> Measurement.parse("{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Measurement.InvalidMeasurementException.class).hasMessageContaining("schema version 2");
        assertThatThrownBy(() -> Measurement.parse("""
                {"schemaVersion":1,"siteId":"paris-01","measuredAt":"2026-10-28T10:00:00Z","messageKey":"k"}""".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Measurement.InvalidMeasurementException.class).hasMessageContaining("deviceId");
    }
}
