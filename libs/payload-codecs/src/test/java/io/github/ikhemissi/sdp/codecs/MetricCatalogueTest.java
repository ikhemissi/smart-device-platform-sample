package io.github.ikhemissi.sdp.codecs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Spec: payload-decoding — "Canonical metrics and units" / "Metric catalogue is discoverable". */
class MetricCatalogueTest {

    private static final Map<String, String> SPEC_UNITS = Map.ofEntries(
            Map.entry("temperature", "°C"), Map.entry("humidity", "%"), Map.entry("pressure", "hPa"),
            Map.entry("acceleration_x", "g"), Map.entry("acceleration_y", "g"), Map.entry("acceleration_z", "g"),
            Map.entry("battery_voltage", "V"), Map.entry("battery_level", "%"), Map.entry("tx_power", "dBm"),
            Map.entry("movement_counter", ""), Map.entry("measurement_sequence", ""), Map.entry("packet_id", ""),
            Map.entry("co2", "ppm"), Map.entry("illuminance", "lx"), Map.entry("analog_input", ""),
            Map.entry("analog_output", ""), Map.entry("digital_input", ""), Map.entry("digital_output", ""),
            Map.entry("presence", ""), Map.entry("latitude", "°"), Map.entry("longitude", "°"), Map.entry("altitude", "m"));

    @Test
    void catalogueMatchesTheSpecifiedMetricsAndUnits() {
        Map<String, String> catalogue = Arrays.stream(Metric.values())
                .collect(Collectors.toMap(Metric::key, Metric::unit));

        assertThat(catalogue).isEqualTo(SPEC_UNITS);
    }

    @Test
    void everyMetricHasADescription() {
        assertThat(Metric.values()).allSatisfy(m -> assertThat(m.description()).isNotBlank());
    }

    @Test
    void channelSuffixedKeysResolveToTheirBaseMetric() {
        assertThat(Metric.fromKey("temperature_ch3")).contains(Metric.TEMPERATURE);
        assertThat(Metric.fromKey("acceleration_x_ch12")).contains(Metric.ACCELERATION_X);
        assertThat(Metric.fromKey("humidity")).contains(Metric.HUMIDITY);
        assertThat(Metric.fromKey("unknown")).isEmpty();
        assertThat(Metric.fromKey(null)).isEmpty();
    }

    @Test
    void everyCodecOnlyUsesCatalogueMetrics() {
        for (PayloadFormat format : PayloadFormat.values()) {
            assertThat(PayloadCodecs.forFormat(format).supportedMetrics()).as(format.id()).isNotEmpty();
        }
    }
}
