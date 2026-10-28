package io.github.ikhemissi.sdp.codecs;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Canonical JSON form of decoded metrics and attributes, as stored in {@code measurements.metrics} and
 * {@code measurements.attributes} (jsonb). Numbers keep the decoder's exact decimal values.
 */
public final class MetricsJson {

    private MetricsJson() {
    }

    public static String numbers(Map<String, BigDecimal> values) {
        StringBuilder sb = new StringBuilder("{");
        values.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            string(sb, k).append(':').append(v.toPlainString());
        });
        return sb.append('}').toString();
    }

    public static String strings(Map<String, String> values) {
        StringBuilder sb = new StringBuilder("{");
        values.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            string(string(sb, k).append(':'), v);
        });
        return sb.append('}').toString();
    }

    private static StringBuilder string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"');
    }
}
