package io.github.ikhemissi.sdp.codecs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/** Byte-level and decimal helpers shared by codecs. */
final class Codecs {

    private Codecs() {
    }

    static int u8(byte[] b, int offset) {
        return b[offset] & 0xFF;
    }

    static int u16be(byte[] b, int offset) {
        return (u8(b, offset) << 8) | u8(b, offset + 1);
    }

    static int s16be(byte[] b, int offset) {
        return (short) u16be(b, offset);
    }

    static int s24be(byte[] b, int offset) {
        int v = (u8(b, offset) << 16) | (u8(b, offset + 1) << 8) | u8(b, offset + 2);
        return (v << 8) >> 8;
    }

    static long uLe(byte[] b, int offset, int length) {
        long v = 0;
        for (int i = length - 1; i >= 0; i--) {
            v = (v << 8) | u8(b, offset + i);
        }
        return v;
    }

    static long sLe(byte[] b, int offset, int length) {
        long v = uLe(b, offset, length);
        int shift = 64 - 8 * length;
        return (v << shift) >> shift;
    }

    static void putU16be(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >>> 8);
        b[offset + 1] = (byte) value;
    }

    static void putS24be(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >>> 16);
        b[offset + 1] = (byte) (value >>> 8);
        b[offset + 2] = (byte) value;
    }

    static void putLe(byte[] b, int offset, int length, long value) {
        for (int i = 0; i < length; i++) {
            b[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    /** Exact decimal {@code raw × factor} without trailing zeros (and never in exponent notation). */
    static BigDecimal scaled(long raw, BigDecimal factor) {
        return normalize(BigDecimal.valueOf(raw).multiply(factor));
    }

    static BigDecimal normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** Inverse of {@link #scaled}: rounds {@code value / factor} half-up and clamps it to {@code [min, max]}. */
    static long unscaled(Number value, BigDecimal factor, long min, long max) {
        BigDecimal raw = new BigDecimal(value.toString()).divide(factor, 0, RoundingMode.HALF_UP);
        return Math.max(min, Math.min(max, raw.longValueExact()));
    }

    static void requireSupported(Map<String, ? extends Number> metrics, Set<Metric> supported, PayloadFormat format) {
        for (String key : metrics.keySet()) {
            Metric metric = Metric.fromKey(key).orElse(null);
            if (metric == null || !supported.contains(metric) || (!key.equals(metric.key()) && format != PayloadFormat.CAYENNE_LPP)) {
                throw new IllegalArgumentException("Metric '" + key + "' is not supported by " + format.id());
            }
        }
    }

    static DecodeResult.Rejected malformed(String detail) {
        return new DecodeResult.Rejected(RejectionReason.MALFORMED_PAYLOAD, detail);
    }
}
