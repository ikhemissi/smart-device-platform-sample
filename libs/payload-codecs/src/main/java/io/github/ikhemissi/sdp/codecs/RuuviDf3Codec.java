package io.github.ikhemissi.sdp.codecs;

import static io.github.ikhemissi.sdp.codecs.Codecs.putU16be;
import static io.github.ikhemissi.sdp.codecs.Codecs.s16be;
import static io.github.ikhemissi.sdp.codecs.Codecs.scaled;
import static io.github.ikhemissi.sdp.codecs.Codecs.u16be;
import static io.github.ikhemissi.sdp.codecs.Codecs.u8;
import static io.github.ikhemissi.sdp.codecs.Codecs.unscaled;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Ruuvi Data Format 3 (RAWv1): https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-3-rawv1.
 * The format has no "not available" sentinel; missing values are encoded as 0, as recommended by Ruuvi.
 */
final class RuuviDf3Codec implements PayloadCodec {

    static final int FORMAT_BYTE = 0x03;
    static final int LENGTH = 14;

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal HUNDREDTH = new BigDecimal("0.01");
    private static final BigDecimal MILLI = new BigDecimal("0.001");
    private static final int PRESSURE_OFFSET_PA = 50_000;

    private static final Set<Metric> SUPPORTED = EnumSet.of(Metric.TEMPERATURE, Metric.HUMIDITY, Metric.PRESSURE,
            Metric.ACCELERATION_X, Metric.ACCELERATION_Y, Metric.ACCELERATION_Z, Metric.BATTERY_VOLTAGE);

    @Override
    public PayloadFormat format() {
        return PayloadFormat.RUUVI_DF3;
    }

    @Override
    public Set<Metric> supportedMetrics() {
        return SUPPORTED;
    }

    @Override
    public DecodeResult decode(byte[] p) {
        if (p.length != LENGTH) {
            return Codecs.malformed("ruuvi-df3 payload must be " + LENGTH + " bytes, got " + p.length);
        }
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put(Metric.TEMPERATURE.key(), temperature(u8(p, 2), u8(p, 3)));
        m.put(Metric.HUMIDITY.key(), scaled(u8(p, 1), HALF));
        m.put(Metric.PRESSURE.key(), scaled(u16be(p, 4) + PRESSURE_OFFSET_PA, HUNDREDTH));
        m.put(Metric.ACCELERATION_X.key(), scaled(s16be(p, 6), MILLI));
        m.put(Metric.ACCELERATION_Y.key(), scaled(s16be(p, 8), MILLI));
        m.put(Metric.ACCELERATION_Z.key(), scaled(s16be(p, 10), MILLI));
        m.put(Metric.BATTERY_VOLTAGE.key(), scaled(u16be(p, 12), MILLI));
        return new DecodeResult.Decoded(PayloadFormat.RUUVI_DF3, m, Map.of());
    }

    private static BigDecimal temperature(int signAndInteger, int fraction) {
        long hundredths = (signAndInteger & 0x7F) * 100L + fraction;
        return scaled((signAndInteger & 0x80) != 0 ? -hundredths : hundredths, HUNDREDTH);
    }

    @Override
    public byte[] encode(Map<String, ? extends Number> metrics, Map<String, String> attributes) {
        Codecs.requireSupported(metrics, SUPPORTED, format());
        byte[] p = new byte[LENGTH];
        p[0] = FORMAT_BYTE;
        p[1] = (byte) value(metrics, Metric.HUMIDITY, HALF, 0, 255);
        long hundredths = value(metrics, Metric.TEMPERATURE, HUNDREDTH, -12_799, 12_799);
        long magnitude = Math.abs(hundredths);
        p[2] = (byte) ((hundredths < 0 ? 0x80 : 0) | (int) (magnitude / 100));
        p[3] = (byte) (magnitude % 100);
        Number pressure = metrics.get(Metric.PRESSURE.key());
        putU16be(p, 4, pressure == null ? 0
                : (int) unscaled(pressure, HUNDREDTH, PRESSURE_OFFSET_PA, PRESSURE_OFFSET_PA + 65_535L) - PRESSURE_OFFSET_PA);
        putU16be(p, 6, (int) value(metrics, Metric.ACCELERATION_X, MILLI, -32_767, 32_767));
        putU16be(p, 8, (int) value(metrics, Metric.ACCELERATION_Y, MILLI, -32_767, 32_767));
        putU16be(p, 10, (int) value(metrics, Metric.ACCELERATION_Z, MILLI, -32_767, 32_767));
        putU16be(p, 12, (int) value(metrics, Metric.BATTERY_VOLTAGE, MILLI, 0, 65_535));
        return p;
    }

    private static long value(Map<String, ? extends Number> metrics, Metric metric, BigDecimal factor, long min, long max) {
        Number value = metrics.get(metric.key());
        return value == null ? 0 : unscaled(value, factor, min, max);
    }
}
