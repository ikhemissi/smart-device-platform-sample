package io.github.ikhemissi.sdp.simulator.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.ikhemissi.sdp.codecs.DecodeResult;
import io.github.ikhemissi.sdp.codecs.PayloadCodecs;
import io.github.ikhemissi.sdp.codecs.PayloadFormat;
import io.github.ikhemissi.sdp.codecs.RejectionReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Spec device-simulation: "Realistic readings", "Real-format payloads", "Fault and anomaly injection".
 */
class ReadingModelTest {

    private static final Instant START = Instant.parse("2026-10-27T00:00:00Z");

    private static SimulatedDevice device(PayloadFormat format) {
        return new SimulatedDevice("paris-01-" + SimulatedDevice.shortName(format) + "-001", "paris-01", format);
    }

    @ParameterizedTest
    @EnumSource(PayloadFormat.class)
    void consecutiveTemperaturesAreContinuous(PayloadFormat format) {
        ReadingModel model = new ReadingModel(device(format), List.of(), START);
        double previous = Double.NaN;
        for (int i = 0; i < 100; i++) {
            double t = model.next(START.plusSeconds(30L * i)).metrics().get("temperature").doubleValue();
            if (!Double.isNaN(previous)) {
                assertThat(Math.abs(t - previous)).as("reading %d", i).isLessThan(1.0);
            }
            previous = t;
        }
    }

    @ParameterizedTest
    @EnumSource(PayloadFormat.class)
    void twoDaysOfReadingsEncodeIntoValidPayloads(PayloadFormat format) {
        SimulatedDevice device = device(format);
        ReadingModel model = new ReadingModel(device, List.of(), START);
        PayloadFactory payloads = new PayloadFactory(0, 1);
        for (int i = 0; i < 2 * 24 * 60; i++) {
            ReadingModel.Reading reading = model.next(START.plus(Duration.ofMinutes(i)));
            DecodeResult result = PayloadCodecs.decode(payloads.build(device, reading).bytes());

            assertThat(result).as("minute %d", i).isInstanceOfSatisfying(DecodeResult.Decoded.class, d -> {
                assertThat(d.format()).isEqualTo(format);
                assertThat(d.metrics().get("temperature").doubleValue()).isBetween(5.0, 35.0);
                assertThat(d.metrics().get("humidity").doubleValue()).isBetween(5.0, 95.0);
                assertThat(d.metrics().get("pressure").doubleValue()).isBetween(990.0, 1030.0);
            });
        }
    }

    @Test
    void temperatureFollowsADailyCycle() {
        ReadingModel model = new ReadingModel(device(PayloadFormat.RUUVI_DF5), List.of(), START);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < 24 * 12; i++) {
            double t = model.next(START.plus(Duration.ofMinutes(5L * i))).metrics().get("temperature").doubleValue();
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        assertThat(max - min).isGreaterThan(2.5);
    }

    @Test
    void differentDevicesHaveDifferentBaselines() {
        double a = new ReadingModel(new SimulatedDevice("paris-01-ruuvi5-001", "paris-01", PayloadFormat.RUUVI_DF5), List.of(), START)
                .next(START).metrics().get("temperature").doubleValue();
        double b = new ReadingModel(new SimulatedDevice("paris-01-ruuvi5-002", "paris-01", PayloadFormat.RUUVI_DF5), List.of(), START)
                .next(START).metrics().get("temperature").doubleValue();
        assertThat(a).isNotCloseTo(b, within(0.01));
    }

    @Test
    void temperatureAnomalyOnDemand() {
        SimulatedDevice device = device(PayloadFormat.RUUVI_DF5);
        Anomaly anomaly = Anomaly.parse(device.deviceId() + ":temperature=12:5m");
        ReadingModel model = new ReadingModel(device, List.of(anomaly), START);

        for (int s = 0; s < 300; s += 30) {
            assertThat(model.next(START.plusSeconds(s)).metrics().get("temperature").doubleValue()).isCloseTo(12.0, within(0.3));
        }
        double after = model.next(START.plusSeconds(330)).metrics().get("temperature").doubleValue();
        assertThat(after).isNotCloseTo(12.0, within(1.0));
    }

    @Test
    void anomalyWithDelayAndForOtherDevice() {
        Anomaly anomaly = Anomaly.parse("paris-01-ruuvi5-001:humidity=95.5:10m:2m");

        assertThat(anomaly.value()).isEqualTo(95.5);
        assertThat(anomaly.isActive("paris-01-ruuvi5-001", START, START.plusSeconds(60))).isFalse();
        assertThat(anomaly.isActive("paris-01-ruuvi5-001", START, START.plusSeconds(180))).isTrue();
        assertThat(anomaly.isActive("paris-01-ruuvi5-001", START, START.plusSeconds(721))).isFalse();
        assertThat(anomaly.isActive("paris-01-ruuvi5-002", START, START.plusSeconds(180))).isFalse();
        assertThatThrownBy(() -> Anomaly.parse("paris-01-ruuvi5-001:temperature")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(PayloadFormat.class)
    void corruptedPayloadsAreRejectedByTheDecoder(PayloadFormat format) {
        SimulatedDevice device = device(format);
        ReadingModel model = new ReadingModel(device, List.of(), START);
        PayloadFactory payloads = new PayloadFactory(1.0, 42);
        for (int i = 0; i < 200; i++) {
            PayloadFactory.Payload payload = payloads.build(device, model.next(START.plusSeconds(30L * i)));
            assertThat(payload.corrupted()).isTrue();
            assertThat(PayloadCodecs.decode(payload.bytes())).isInstanceOfSatisfying(DecodeResult.Rejected.class,
                    r -> assertThat(r.reason()).isIn(RejectionReason.UNKNOWN_FORMAT, RejectionReason.MALFORMED_PAYLOAD));
        }
    }

    @Test
    void corruptionRateIsApplied() {
        SimulatedDevice device = device(PayloadFormat.BTHOME_V2);
        ReadingModel model = new ReadingModel(device, List.of(), START);
        PayloadFactory payloads = new PayloadFactory(0.01, 7);
        int corrupted = 0;
        for (int i = 0; i < 20_000; i++) {
            corrupted += payloads.build(device, model.next(START.plusSeconds(i))).corrupted() ? 1 : 0;
        }
        assertThat(corrupted).isBetween(130, 270);
    }
}
