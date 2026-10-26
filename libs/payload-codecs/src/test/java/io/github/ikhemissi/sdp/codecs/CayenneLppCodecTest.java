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

/**
 * Spec: payload-decoding — "Cayenne LPP decoding". Vectors are the myDevices documentation examples prefixed with
 * the 0xCA format marker.
 */
class CayenneLppCodecTest {

    private final PayloadCodec codec = PayloadCodecs.forFormat(PayloadFormat.CAYENNE_LPP);

    @Test
    void twoTemperatureChannels() {
        assertMetrics(decoded("CA03670110056700FF"), "temperature_ch3", "27.2", "temperature_ch5", "25.5");
    }

    @Test
    void singleNegativeTemperature() {
        assertMetrics(decoded("CA0167FFD7"), "temperature", "-4.1");
    }

    @Test
    void accelerometer() {
        assertMetrics(decoded("CA067104D2FB2E0000"),
                "acceleration_x", "1.234", "acceleration_y", "-1.234", "acceleration_z", "0");
    }

    @Test
    void gps() {
        assertMetrics(decoded("CA018806765FF2960A0003E8"),
                "latitude", "42.3519", "longitude", "-87.9094", "altitude", "10");
    }

    @Test
    void mixedSensors() {
        assertMetrics(decoded("CA016700E1026850037327A6046501F4"),
                "temperature", "22.5", "humidity", "40", "pressure", "1015", "illuminance", "500");
    }

    @Test
    void truncatedValueIsMalformed() {
        assertThat(rejected("CA0167FF").reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD);
        assertThat(rejected("CA0167FFD701").reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD);
    }

    @Test
    void unknownTypeIsMalformed() {
        DecodeResult.Rejected rejected = rejected("CA0186000100020003");

        assertThat(rejected.reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD);
        assertThat(rejected.detail()).contains("0x86");
    }

    @Test
    void emptyFrameHasNoSupportedData() {
        assertThat(rejected("CA").reason()).isEqualTo(RejectionReason.NO_SUPPORTED_DATA);
    }

    @Test
    void encoderReproducesTheTwoChannelExample() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("temperature_ch5", 25.5);
        metrics.put("temperature_ch3", 27.2);

        assertThat(hex(codec.encode(metrics))).isEqualTo("CA03670110056700FF");
    }

    @Test
    void encoderReproducesTheAccelerometerAndGpsExamples() {
        assertThat(hex(codec.encode(Map.of("acceleration_x_ch6", 1.234, "acceleration_y_ch6", -1.234, "acceleration_z_ch6", 0))))
                .isEqualTo("CA067104D2FB2E0000");
        assertThat(hex(codec.encode(Map.of("latitude", 42.3519, "longitude", -87.9094, "altitude", 10))))
                .isEqualTo("CA018806765FF2960A0003E8");
    }

    @Property(tries = 500)
    void roundTripProperty(@ForAll @Scale(4) @DoubleRange(min = -40, max = 85) double temperature,
                           @ForAll @Scale(4) @DoubleRange(min = 0, max = 100) double humidity,
                           @ForAll @Scale(4) @DoubleRange(min = 300, max = 1100) double pressure,
                           @ForAll @IntRange(min = 0, max = 65_535) int illuminance,
                           @ForAll @Scale(4) @DoubleRange(min = -300, max = 300) double analog,
                           @ForAll @IntRange(min = 0, max = 1) int presence,
                           @ForAll @Scale(4) @DoubleRange(min = -90, max = 90) double latitude,
                           @ForAll @Scale(4) @DoubleRange(min = -180, max = 180) double longitude,
                           @ForAll @Scale(4) @DoubleRange(min = -400, max = 9000) double altitude,
                           @ForAll @Scale(4) @DoubleRange(min = -16, max = 16) double acceleration) {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("temperature", temperature);
        metrics.put("humidity", humidity);
        metrics.put("pressure", pressure);
        metrics.put("illuminance", illuminance);
        metrics.put("analog_input", analog);
        metrics.put("presence", presence);
        metrics.put("latitude_ch2", latitude);
        metrics.put("longitude_ch2", longitude);
        metrics.put("altitude_ch2", altitude);
        metrics.put("acceleration_x_ch3", acceleration);
        metrics.put("acceleration_y_ch3", -acceleration);
        metrics.put("acceleration_z_ch3", acceleration / 4);

        DecodeResult.Decoded d = (DecodeResult.Decoded) PayloadCodecs.decode(codec.encode(metrics));

        // Each metric is on a single channel, so canonical keys are used after decoding.
        assertClose(d, "temperature", temperature, 0.05);
        assertClose(d, "humidity", humidity, 0.25);
        assertClose(d, "pressure", pressure, 0.05);
        assertClose(d, "illuminance", illuminance, 0);
        assertClose(d, "analog_input", analog, 0.005);
        assertClose(d, "presence", presence, 0);
        assertClose(d, "latitude", latitude, 0.00005);
        assertClose(d, "longitude", longitude, 0.00005);
        assertClose(d, "altitude", altitude, 0.005);
        assertClose(d, "acceleration_x", acceleration, 0.0005);
        assertClose(d, "acceleration_y", -acceleration, 0.0005);
        assertClose(d, "acceleration_z", acceleration / 4, 0.0005);
    }
}
