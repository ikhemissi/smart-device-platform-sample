package io.github.ikhemissi.sdp.codecs;

import static io.github.ikhemissi.sdp.codecs.RuuviDf5CodecTest.assertClose;
import static io.github.ikhemissi.sdp.codecs.TestSupport.assertMetrics;
import static io.github.ikhemissi.sdp.codecs.TestSupport.decoded;
import static io.github.ikhemissi.sdp.codecs.TestSupport.hex;
import static io.github.ikhemissi.sdp.codecs.TestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;

/** Spec: payload-decoding — "Ruuvi Data Format 3 decoding". */
class RuuviDf3CodecTest {

    private static final String VALID = "03291A1ECE1EFC18F94202CA0B53";
    private static final String MAXIMUM = "03FF7F63FFFF7FFF7FFF7FFFFFFF";
    private static final String MINIMUM = "0300FF6300008001800180010000";

    private final PayloadCodec codec = PayloadCodecs.forFormat(PayloadFormat.RUUVI_DF3);

    @Test
    void officialValidVector() {
        assertMetrics(decoded(VALID), "temperature", "26.3", "humidity", "20.5", "pressure", "1027.66",
                "acceleration_x", "-1", "acceleration_y", "-1.726", "acceleration_z", "0.714", "battery_voltage", "2.899");
    }

    @Test
    void officialMaximumVector() {
        assertMetrics(decoded(MAXIMUM), "temperature", "127.99", "humidity", "127.5", "pressure", "1155.35",
                "acceleration_x", "32.767", "acceleration_y", "32.767", "acceleration_z", "32.767", "battery_voltage", "65.535");
    }

    @Test
    void officialMinimumVector() {
        assertMetrics(decoded(MINIMUM), "temperature", "-127.99", "humidity", "0", "pressure", "500",
                "acceleration_x", "-32.767", "acceleration_y", "-32.767", "acceleration_z", "-32.767", "battery_voltage", "0");
    }

    @Test
    void officialVectorsAreReproducedByTheEncoder() {
        for (String vector : new String[] {VALID, MAXIMUM, MINIMUM}) {
            DecodeResult.Decoded d = decoded(vector);
            assertThat(hex(codec.encode(d.metrics()))).as(vector).isEqualTo(vector);
        }
    }

    @Test
    void negativeTemperature() {
        byte[] payload = hex(VALID);
        payload[2] = (byte) 0x81;
        payload[3] = (byte) 0x45;

        DecodeResult.Decoded d = (DecodeResult.Decoded) PayloadCodecs.decode(payload);

        assertThat(d.metrics().get("temperature")).isEqualByComparingTo("-1.69");
    }

    @Test
    void wrongLengthIsMalformed() {
        assertThat(rejected(VALID + "00").reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD);
    }

    @Test
    void missingMetricsAreEncodedAsZero() {
        DecodeResult.Decoded d = (DecodeResult.Decoded) PayloadCodecs.decode(codec.encode(Map.of("temperature", -4.2)));

        assertThat(d.metrics().get("temperature")).isEqualByComparingTo("-4.2");
        assertThat(d.metrics().get("humidity")).isEqualByComparingTo("0");
    }

    @Property(tries = 500)
    void roundTripProperty(@ForAll @Scale(4) @DoubleRange(min = -127.99, max = 127.99) double temperature,
                           @ForAll @Scale(4) @DoubleRange(min = 0, max = 127.5) double humidity,
                           @ForAll @Scale(4) @DoubleRange(min = 500, max = 1155.35) double pressure,
                           @ForAll @Scale(4) @DoubleRange(min = -32.767, max = 32.767) double acceleration,
                           @ForAll @Scale(4) @DoubleRange(min = 0, max = 65.535) double voltage) {
        Map<String, Number> metrics = Map.of("temperature", temperature, "humidity", humidity, "pressure", pressure,
                "acceleration_x", acceleration, "acceleration_y", -acceleration, "acceleration_z", acceleration / 3,
                "battery_voltage", voltage);

        DecodeResult.Decoded d = (DecodeResult.Decoded) PayloadCodecs.decode(codec.encode(metrics));

        assertClose(d, "temperature", temperature, 0.005);
        assertClose(d, "humidity", humidity, 0.25);
        assertClose(d, "pressure", pressure, 0.005);
        assertClose(d, "acceleration_x", acceleration, 0.0005);
        assertClose(d, "acceleration_y", -acceleration, 0.0005);
        assertClose(d, "acceleration_z", acceleration / 3, 0.0005);
        assertClose(d, "battery_voltage", voltage, 0.0005);
    }
}
