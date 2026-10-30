package io.github.ikhemissi.sdp.fleet;

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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void pressureStaysContinuousWhenTheSimulatorRestarts() {
        SimulatedDevice device = device(PayloadFormat.RUUVI_DF5);
        Instant now = START.plus(Duration.ofDays(3));
        // A model started three days ago and one started just now (a restart) agree at the same instant.
        double running = new ReadingModel(device, List.of(), START).next(now).metrics().get("pressure").doubleValue();
        double restarted = new ReadingModel(device, List.of(), now).next(now).metrics().get("pressure").doubleValue();
        assertThat(restarted).isCloseTo(running, within(0.5));
    }

    @Test
    void pressureDriftsSlowlyLikeTheWeather() {
        ReadingModel model = new ReadingModel(device(PayloadFormat.RUUVI_DF5), List.of(), START);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double previous = Double.NaN;
        for (int i = 0; i < 7 * 24 * 60; i++) {
            double p = model.next(START.plus(Duration.ofMinutes(i))).metrics().get("pressure").doubleValue();
            if (!Double.isNaN(previous)) {
                assertThat(Math.abs(p - previous)).as("minute %d", i).isLessThan(0.5);
            }
            previous = p;
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        assertThat(max - min).as("range over a week").isBetween(3.0, 16.0);
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

    private static double battery(ReadingModel model, Instant at) {
        return model.next(at).metrics().get("battery_voltage").doubleValue();
    }

    /** The instant of the device's next new battery (a jump up of the noise-free voltage) after {@code from}. */
    private static Instant nextReplacement(ReadingModel model, Instant from) {
        Instant t = from;
        double previous = model.batteryVolts(t);
        for (int h = 1; h <= 24 * 401; h++) {
            Instant next = from.plus(Duration.ofHours(h));
            double v = model.batteryVolts(next);
            if (v > previous + 0.01) {
                return next;
            }
            previous = v;
            t = next;
        }
        throw new AssertionError("no replacement within a period");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void batteryStaysContinuousWhenTheSimulatorRestarts(boolean aging) {
        SimulatedDevice device = device(PayloadFormat.RUUVI_DF5);
        Instant now = START.plus(Duration.ofDays(3));
        double running = battery(new ReadingModel(device, List.of(), START, aging), now);
        double restarted = battery(new ReadingModel(device, List.of(), now, aging), now);
        assertThat(restarted).isCloseTo(running, within(0.01));
    }

    @Test
    void agingDeviceCrossesTheCutOffWithinSixteenDaysThenGetsANewBattery() {
        ReadingModel model = new ReadingModel(device(PayloadFormat.RUUVI_DF5), List.of(), START, true);
        Instant replaced = nextReplacement(model, START);
        assertThat(Duration.between(START, replaced)).isLessThanOrEqualTo(Duration.ofDays(16));
        assertThat(battery(model, replaced)).isCloseTo(ReadingModel.NEW_BATTERY_VOLTS, within(0.01));

        Instant crossing = null;
        double previous = Double.NaN;
        for (int h = 0; h < 16 * 24; h++) {
            Instant at = replaced.plus(Duration.ofHours(h));
            double v = model.batteryVolts(at);
            if (!Double.isNaN(previous) && h < 15 * 24) {
                assertThat(previous - v).as("hourly decline at hour %d", h).isBetween(0.0, 0.002);
            }
            if (crossing == null && v < 2.5) {
                crossing = at;
            }
            previous = v;
        }
        assertThat(crossing).as("crosses 2.5 V").isNotNull();
        assertThat(Duration.between(replaced, crossing)).isBetween(Duration.ofDays(14), Duration.ofDays(16));
        Instant again = nextReplacement(model, crossing);
        assertThat(Duration.between(replaced, again)).isEqualTo(Duration.ofDays(16));
        assertThat(model.batteryVolts(again)).isCloseTo(ReadingModel.NEW_BATTERY_VOLTS, within(0.001));
    }

    @ParameterizedTest
    @EnumSource(value = PayloadFormat.class, names = {"RUUVI_DF5", "RUUVI_DF3"})
    void normalDeviceStaysAboveTwoPointSevenFiveVoltsForTwoHundredDaysAfterANewBattery(PayloadFormat format) {
        ReadingModel model = new ReadingModel(device(format), List.of(), START);
        Instant replaced = nextReplacement(model, START);
        for (int d = 0; d < 200; d++) {
            assertThat(battery(model, replaced.plus(Duration.ofDays(d)))).as("day %d", d).isGreaterThan(2.745);
        }
    }

    @Test
    void normalDemoFleetIsWithinTwoHundredDaysOfANewBatteryAtTheBatteryEpoch() {
        for (SimulatedDevice device : SimulatedDevice.plan(List.of("paris-01", "lyon-01", "marseille-01"), 10,
                List.of(PayloadFormat.values()))) {
            assertThat(new ReadingModel(device, List.of(), START).batteryVolts(ReadingModel.BATTERY_EPOCH))
                    .as(device.deviceId()).isBetween(2.75, ReadingModel.NEW_BATTERY_VOLTS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void otherValuesAndMacAreUnchangedByTheBatteryModel(boolean aging) {
        // Golden values from the reading model before M9 (device paris-01-ruuvi5-003, second reading).
        ReadingModel model = new ReadingModel(new SimulatedDevice("paris-01-ruuvi5-003", "paris-01", PayloadFormat.RUUVI_DF5),
                List.of(), START, aging);
        model.next(START.plusSeconds(3600));
        ReadingModel.Reading reading = model.next(START.plusSeconds(3630));
        assertThat(reading.attributes()).containsEntry("mac", "DE:37:72:34:B3:65");
        assertThat(reading.metrics().get("temperature").doubleValue()).isEqualTo(21.82974911574985);
        assertThat(reading.metrics().get("humidity").doubleValue()).isEqualTo(63.73008640549239);
        assertThat(reading.metrics().get("pressure").doubleValue()).isEqualTo(1006.2189388191229);
        assertThat(reading.metrics().get("acceleration_z").doubleValue()).isEqualTo(1.002160962093985);
        assertThat(reading.metrics().get("measurement_sequence").intValue()).isEqualTo(552);

        ReadingModel bthome = new ReadingModel(new SimulatedDevice("paris-01-bthome-003", "paris-01", PayloadFormat.BTHOME_V2),
                List.of(), START, aging);
        bthome.next(START.plusSeconds(3600));
        Map<String, Number> m = bthome.next(START.plusSeconds(3630)).metrics();
        assertThat(m.get("temperature").doubleValue()).isEqualTo(20.703165948489563);
        assertThat(m.get("co2").intValue()).isEqualTo(469);
        assertThat(m.get("packet_id").intValue()).isEqualTo(226);
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
        assertThat(anomaly.ramp()).isZero();
    }

    @Test
    void rampedAnomalyWarmsUpGraduallyWithNoiseThenReturns() {
        SimulatedDevice device = device(PayloadFormat.RUUVI_DF5);
        Anomaly anomaly = Anomaly.parse(device.deviceId() + ":temperature=35~5m:10m:1m");
        assertThat(anomaly.ramp()).isEqualTo(Duration.ofMinutes(5));
        ReadingModel model = new ReadingModel(device, List.of(anomaly), START);

        double before = model.next(START.plusSeconds(30)).metrics().get("temperature").doubleValue();
        double previous = before;
        List<Double> held = new java.util.ArrayList<>();
        // Ramp up 1-6 min, held 6-16 min, ramp down 16-21 min.
        for (int s = 60; s <= 22 * 60; s += 30) {
            double t = model.next(START.plusSeconds(s)).metrics().get("temperature").doubleValue();
            assertThat(Math.abs(t - previous)).as("second %d", s).isLessThan(3.0);
            if (s >= 6 * 60 && s < 16 * 60) {
                held.add(t);
            }
            previous = t;
        }
        assertThat(held).hasSize(20).allSatisfy(t -> assertThat(t).isCloseTo(35.0, within(0.6)));
        assertThat(held.stream().distinct().count()).as("noisy, not constant").isGreaterThan(5);
        assertThat(previous).as("back to normal after the ramp down").isCloseTo(before, within(2.0));
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
