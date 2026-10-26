package io.github.ikhemissi.sdp.codecs;

import static io.github.ikhemissi.sdp.codecs.TestSupport.assertMetrics;
import static io.github.ikhemissi.sdp.codecs.TestSupport.decoded;
import static io.github.ikhemissi.sdp.codecs.TestSupport.hex;
import static io.github.ikhemissi.sdp.codecs.TestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** Spec: payload-decoding — "Ruuvi Data Format 5 decoding", "Not-available values are omitted", round trips. */
class RuuviDf5CodecTest {

    private static final String VALID = "0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F";
    private static final String MAXIMUM = "057FFFFFFEFFFE7FFF7FFF7FFFFFDEFEFFFECBB8334C884F";
    private static final String MINIMUM = "058001000000008001800180010000000000CBB8334C884F";

    private final PayloadCodec codec = PayloadCodecs.forFormat(PayloadFormat.RUUVI_DF5);

    @Test
    void officialValidVector() {
        DecodeResult.Decoded d = decoded(VALID);

        assertMetrics(d, "temperature", "24.3", "humidity", "53.49", "pressure", "1000.44",
                "acceleration_x", "0.004", "acceleration_y", "-0.004", "acceleration_z", "1.036",
                "battery_voltage", "2.977", "tx_power", "4", "movement_counter", "66", "measurement_sequence", "205");
        assertThat(d.attributes()).containsExactly(Map.entry("mac", "CB:B8:33:4C:88:4F"));
    }

    @Test
    void officialMaximumVector() {
        assertMetrics(decoded(MAXIMUM), "temperature", "163.835", "humidity", "163.835", "pressure", "1155.34",
                "acceleration_x", "32.767", "acceleration_y", "32.767", "acceleration_z", "32.767",
                "battery_voltage", "3.646", "tx_power", "20", "movement_counter", "254", "measurement_sequence", "65534");
    }

    @Test
    void officialMinimumVector() {
        assertMetrics(decoded(MINIMUM), "temperature", "-163.835", "humidity", "0", "pressure", "500",
                "acceleration_x", "-32.767", "acceleration_y", "-32.767", "acceleration_z", "-32.767",
                "battery_voltage", "1.6", "tx_power", "-40", "movement_counter", "0", "measurement_sequence", "0");
    }

    @Test
    void officialVectorsAreReproducedByTheEncoder() {
        for (String vector : new String[] {VALID, MAXIMUM, MINIMUM}) {
            DecodeResult.Decoded d = decoded(vector);
            assertThat(hex(codec.encode(d.metrics(), d.attributes()))).as(vector).isEqualTo(vector);
        }
    }

    @Test
    void humidityNotAvailableIsOmitted() {
        byte[] payload = hex(VALID);
        payload[3] = (byte) 0xFF;
        payload[4] = (byte) 0xFF;

        DecodeResult.Decoded d = (DecodeResult.Decoded) PayloadCodecs.decode(payload);

        assertThat(d.metrics()).doesNotContainKey("humidity").containsKeys("temperature", "pressure");
    }

    @Test
    void allFieldsNotAvailable() {
        DecodeResult.Decoded d = decoded("058000FFFFFFFF800080008000FFFFFFFFFFFFFFFFFFFFFF");

        assertThat(d.metrics()).isEmpty();
        assertThat(d.attributes()).isEmpty();
    }

    @Test
    void truncatedPayloadIsMalformed() {
        assertThat(rejected(VALID.substring(0, 40)).reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD);
    }

    @Test
    void roundTripWithinResolution() {
        byte[] payload = codec.encode(Map.of("temperature", 21.437, "humidity", 45.0));

        DecodeResult.Decoded d = (DecodeResult.Decoded) PayloadCodecs.decode(payload);

        assertThat(d.metrics().get("temperature")).isEqualByComparingTo("21.435");
        assertThat(d.metrics().get("humidity")).isEqualByComparingTo("45");
        assertThat(d.metrics()).containsOnlyKeys("temperature", "humidity");
    }

    @Test
    void unsupportedMetricIsRefusedByTheEncoder() {
        assertThatThrownBy(() -> codec.encode(Map.of("co2", 400))).isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 500)
    void roundTripProperty(@ForAll @Scale(4) @DoubleRange(min = -163.83, max = 163.83) double temperature,
                           @ForAll @Scale(4) @DoubleRange(min = 0, max = 163.83) double humidity,
                           @ForAll @Scale(4) @DoubleRange(min = 500, max = 1155.34) double pressure,
                           @ForAll @Scale(4) @DoubleRange(min = -32.767, max = 32.767) double acceleration,
                           @ForAll @Scale(4) @DoubleRange(min = 1.6, max = 3.646) double voltage,
                           @ForAll @IntRange(min = -20, max = 10) int txStep,
                           @ForAll @IntRange(min = 0, max = 254) int movement,
                           @ForAll @IntRange(min = 0, max = 65_534) int sequence) {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("temperature", temperature);
        metrics.put("humidity", humidity);
        metrics.put("pressure", pressure);
        metrics.put("acceleration_x", acceleration);
        metrics.put("acceleration_y", -acceleration);
        metrics.put("acceleration_z", acceleration / 2);
        metrics.put("battery_voltage", voltage);
        metrics.put("tx_power", txStep * 2);
        metrics.put("movement_counter", movement);
        metrics.put("measurement_sequence", sequence);

        DecodeResult.Decoded d = (DecodeResult.Decoded) PayloadCodecs.decode(codec.encode(metrics));

        assertClose(d, "temperature", temperature, 0.0025);
        assertClose(d, "humidity", humidity, 0.00125);
        assertClose(d, "pressure", pressure, 0.005);
        assertClose(d, "acceleration_x", acceleration, 0.0005);
        assertClose(d, "acceleration_y", -acceleration, 0.0005);
        assertClose(d, "acceleration_z", acceleration / 2, 0.0005);
        assertClose(d, "battery_voltage", voltage, 0.0005);
        assertClose(d, "tx_power", txStep * 2, 0);
        assertClose(d, "movement_counter", movement, 0);
        assertClose(d, "measurement_sequence", sequence, 0);
    }

    static void assertClose(DecodeResult.Decoded d, String key, double expected, double halfResolution) {
        BigDecimal actual = d.metrics().get(key);
        assertThat(actual).as(key).isNotNull();
        assertThat(actual.doubleValue()).as(key).isCloseTo(expected, within(halfResolution + 1e-9));
    }
}
