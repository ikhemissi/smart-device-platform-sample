package io.github.ikhemissi.sdp.codecs;

import static io.github.ikhemissi.sdp.codecs.RuuviDf5CodecTest.assertClose;
import static io.github.ikhemissi.sdp.codecs.TestSupport.assertMetrics;
import static io.github.ikhemissi.sdp.codecs.TestSupport.decoded;
import static io.github.ikhemissi.sdp.codecs.TestSupport.hex;
import static io.github.ikhemissi.sdp.codecs.TestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Spec: payload-decoding — "BTHome v2 decoding". Vectors are the examples of https://bthome.io/format/. */
class BtHomeV2CodecTest {

    private final PayloadCodec codec = PayloadCodecs.forFormat(PayloadFormat.BTHOME_V2);

    @Test
    void temperatureAndHumidity() {
        assertMetrics(decoded("4002C40903BF13"), "temperature", "25", "humidity", "50.55");
    }

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @CsvSource({
            "400009,         packet_id,       9",
            "400161,         battery_level,   97",
            "4002CA09,       temperature,     25.06",
            "4003BF13,       humidity,        50.55",
            "4004138A01,     pressure,        1008.83",
            "4005138A14,     illuminance,     13460.67",
            "400C020C,       battery_voltage, 3.074",
            "4012E204,       co2,             1250",
            "402E23,         humidity,        35",
            "40451101,       temperature,     27.3"})
    void officialObjectExamples(String payload, String metric, String value) {
        assertMetrics(decoded(payload), metric, value);
    }

    @Test
    void unsupportedObjectIdStopsParsing() {
        DecodeResult.Decoded d = decoded("4002C409FF0103BF13");

        assertMetrics(d, "temperature", "25");
        assertThat(d.attributes()).containsEntry("decode_warning", "UNSUPPORTED_OBJECT_ID:0xFF");
    }

    @Test
    void noSupportedObject() {
        assertThat(rejected("40FF01").reason()).isEqualTo(RejectionReason.NO_SUPPORTED_DATA);
        assertThat(rejected("40").reason()).isEqualTo(RejectionReason.NO_SUPPORTED_DATA);
    }

    @Test
    void truncatedObjectIsMalformed() {
        assertThat(rejected("4002C4").reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD);
    }

    @Test
    void repeatedMetricIsKeptWithAnIndexSuffix() {
        assertMetrics(decoded("4002C40902CA09"), "temperature", "25", "temperature_2", "25.06");
    }

    @Test
    void encoderWritesObjectsInAscendingIdOrder() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("humidity", 50.55);
        metrics.put("temperature", 25.0);

        assertThat(hex(codec.encode(metrics))).isEqualTo("4002C40903BF13");
    }

    @Property(tries = 500)
    void roundTripProperty(@ForAll @IntRange(min = 0, max = 255) int packetId,
                           @ForAll @IntRange(min = 0, max = 100) int batteryLevel,
                           @ForAll @Scale(4) @DoubleRange(min = -40, max = 85) double temperature,
                           @ForAll @Scale(4) @DoubleRange(min = 0, max = 100) double humidity,
                           @ForAll @Scale(4) @DoubleRange(min = 300, max = 1100) double pressure,
                           @ForAll @Scale(4) @DoubleRange(min = 0, max = 100_000) double illuminance,
                           @ForAll @Scale(4) @DoubleRange(min = 0, max = 3.6) double voltage,
                           @ForAll @IntRange(min = 0, max = 10_000) int co2) {
        Map<String, Number> metrics = Map.of("packet_id", packetId, "battery_level", batteryLevel,
                "temperature", temperature, "humidity", humidity, "pressure", pressure,
                "illuminance", illuminance, "battery_voltage", voltage, "co2", co2);

        DecodeResult.Decoded d = (DecodeResult.Decoded) PayloadCodecs.decode(codec.encode(metrics));

        assertClose(d, "packet_id", packetId, 0);
        assertClose(d, "battery_level", batteryLevel, 0);
        assertClose(d, "temperature", temperature, 0.005);
        assertClose(d, "humidity", humidity, 0.005);
        assertClose(d, "pressure", pressure, 0.005);
        assertClose(d, "illuminance", illuminance, 0.005);
        assertClose(d, "battery_voltage", voltage, 0.0005);
        assertClose(d, "co2", co2, 0);
    }
}
