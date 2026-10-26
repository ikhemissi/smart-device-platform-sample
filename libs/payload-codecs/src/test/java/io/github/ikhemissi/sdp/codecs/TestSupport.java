package io.github.ikhemissi.sdp.codecs;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

final class TestSupport {

    private TestSupport() {
    }

    static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex.replace(" ", ""));
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    static DecodeResult.Decoded decoded(String payloadHex) {
        DecodeResult result = PayloadCodecs.decode(hex(payloadHex));
        assertThat(result).as("decode %s", payloadHex).isInstanceOf(DecodeResult.Decoded.class);
        return (DecodeResult.Decoded) result;
    }

    static DecodeResult.Rejected rejected(String payloadHex) {
        DecodeResult result = PayloadCodecs.decode(hex(payloadHex));
        assertThat(result).as("decode %s", payloadHex).isInstanceOf(DecodeResult.Rejected.class);
        return (DecodeResult.Rejected) result;
    }

    /** Asserts exactly these metrics (compared numerically), in any order. Values given as strings, e.g. "24.3". */
    static void assertMetrics(DecodeResult.Decoded decoded, String... keyValues) {
        Map<String, BigDecimal> expected = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            expected.put(keyValues[i], new BigDecimal(keyValues[i + 1]));
        }
        assertThat(decoded.metrics().keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((key, value) ->
                assertThat(decoded.metrics().get(key)).as(key).isEqualByComparingTo(value));
    }
}
