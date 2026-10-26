package io.github.ikhemissi.sdp.codecs;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Outcome of decoding one payload: either decoded metrics or a rejection with its reason. */
public sealed interface DecodeResult {

    /**
     * A decoded payload. Metric values are exact decimals at the format's resolution; insertion order follows
     * the payload.
     */
    record Decoded(PayloadFormat format, Map<String, BigDecimal> metrics, Map<String, String> attributes)
            implements DecodeResult {

        public Decoded {
            Objects.requireNonNull(format, "format");
            metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }

    /** A payload that could not be decoded. */
    record Rejected(RejectionReason reason, String detail) implements DecodeResult {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? "" : detail;
        }
    }
}
