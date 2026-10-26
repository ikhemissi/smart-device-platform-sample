package io.github.ikhemissi.sdp.codecs;

import java.util.Map;
import java.util.Set;

/** Decoder and symmetric encoder for one payload format. Implementations are stateless and thread-safe. */
public interface PayloadCodec {

    PayloadFormat format();

    /** Metrics this codec can encode. */
    Set<Metric> supportedMetrics();

    /** Decodes a complete payload, including its leading format byte. */
    DecodeResult decode(byte[] payload);

    /**
     * Encodes metrics (canonical keys, or {@code <key>_ch<n>} for Cayenne LPP channels) and optional attributes
     * into a complete payload. Missing metrics are encoded as "not available" when the format allows it.
     *
     * @throws IllegalArgumentException if a metric key is not supported by this format
     */
    byte[] encode(Map<String, ? extends Number> metrics, Map<String, String> attributes);

    default byte[] encode(Map<String, ? extends Number> metrics) {
        return encode(metrics, Map.of());
    }
}
