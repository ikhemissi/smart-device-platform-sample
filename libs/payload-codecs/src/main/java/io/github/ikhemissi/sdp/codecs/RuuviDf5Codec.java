package io.github.ikhemissi.sdp.codecs;

import static io.github.ikhemissi.sdp.codecs.Codecs.putU16be;
import static io.github.ikhemissi.sdp.codecs.Codecs.s16be;
import static io.github.ikhemissi.sdp.codecs.Codecs.scaled;
import static io.github.ikhemissi.sdp.codecs.Codecs.u16be;
import static io.github.ikhemissi.sdp.codecs.Codecs.u8;
import static io.github.ikhemissi.sdp.codecs.Codecs.unscaled;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Ruuvi Data Format 5 (RAWv2): https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-5-rawv2 */
final class RuuviDf5Codec implements PayloadCodec {

    static final int FORMAT_BYTE = 0x05;
    static final int LENGTH = 24;

    private static final BigDecimal TEMPERATURE_FACTOR = new BigDecimal("0.005");
    private static final BigDecimal HUMIDITY_FACTOR = new BigDecimal("0.0025");
    private static final BigDecimal HPA = new BigDecimal("0.01");
    private static final BigDecimal MILLI = new BigDecimal("0.001");
    private static final int PRESSURE_OFFSET_PA = 50_000;
    private static final int BATTERY_OFFSET_MV = 1_600;
    private static final int S16_NOT_AVAILABLE = -32_768;
    private static final int U16_NOT_AVAILABLE = 0xFFFF;
    private static final int VOLTAGE_NOT_AVAILABLE = 2_047;
    private static final int TX_NOT_AVAILABLE = 31;
    private static final int MOVEMENT_NOT_AVAILABLE = 0xFF;
    private static final HexFormat MAC = HexFormat.ofDelimiter(":").withUpperCase();

    private static final Set<Metric> SUPPORTED = EnumSet.of(Metric.TEMPERATURE, Metric.HUMIDITY, Metric.PRESSURE,
            Metric.ACCELERATION_X, Metric.ACCELERATION_Y, Metric.ACCELERATION_Z, Metric.BATTERY_VOLTAGE,
            Metric.TX_POWER, Metric.MOVEMENT_COUNTER, Metric.MEASUREMENT_SEQUENCE);

    @Override
    public PayloadFormat format() {
        return PayloadFormat.RUUVI_DF5;
    }

    @Override
    public Set<Metric> supportedMetrics() {
        return SUPPORTED;
    }

    @Override
    public DecodeResult decode(byte[] p) {
        if (p.length != LENGTH) {
            return Codecs.malformed("ruuvi-df5 payload must be " + LENGTH + " bytes, got " + p.length);
        }
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        int temperature = s16be(p, 1);
        if (temperature != S16_NOT_AVAILABLE) {
            m.put(Metric.TEMPERATURE.key(), scaled(temperature, TEMPERATURE_FACTOR));
        }
        int humidity = u16be(p, 3);
        if (humidity != U16_NOT_AVAILABLE) {
            m.put(Metric.HUMIDITY.key(), scaled(humidity, HUMIDITY_FACTOR));
        }
        int pressure = u16be(p, 5);
        if (pressure != U16_NOT_AVAILABLE) {
            m.put(Metric.PRESSURE.key(), scaled(pressure + PRESSURE_OFFSET_PA, HPA));
        }
        Metric[] axes = {Metric.ACCELERATION_X, Metric.ACCELERATION_Y, Metric.ACCELERATION_Z};
        for (int i = 0; i < axes.length; i++) {
            int acceleration = s16be(p, 7 + 2 * i);
            if (acceleration != S16_NOT_AVAILABLE) {
                m.put(axes[i].key(), scaled(acceleration, MILLI));
            }
        }
        int power = u16be(p, 13);
        int voltage = power >>> 5;
        if (voltage != VOLTAGE_NOT_AVAILABLE) {
            m.put(Metric.BATTERY_VOLTAGE.key(), scaled(voltage + BATTERY_OFFSET_MV, MILLI));
        }
        int tx = power & 0x1F;
        if (tx != TX_NOT_AVAILABLE) {
            m.put(Metric.TX_POWER.key(), BigDecimal.valueOf(tx * 2L - 40));
        }
        int movement = u8(p, 15);
        if (movement != MOVEMENT_NOT_AVAILABLE) {
            m.put(Metric.MOVEMENT_COUNTER.key(), BigDecimal.valueOf(movement));
        }
        int sequence = u16be(p, 16);
        if (sequence != U16_NOT_AVAILABLE) {
            m.put(Metric.MEASUREMENT_SEQUENCE.key(), BigDecimal.valueOf(sequence));
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        boolean macAvailable = false;
        for (int i = 18; i < LENGTH; i++) {
            macAvailable |= u8(p, i) != 0xFF;
        }
        if (macAvailable) {
            attributes.put("mac", MAC.formatHex(p, 18, LENGTH));
        }
        return new DecodeResult.Decoded(PayloadFormat.RUUVI_DF5, m, attributes);
    }

    @Override
    public byte[] encode(Map<String, ? extends Number> metrics, Map<String, String> attributes) {
        Codecs.requireSupported(metrics, SUPPORTED, format());
        byte[] p = new byte[LENGTH];
        p[0] = FORMAT_BYTE;
        putU16be(p, 1, encode(metrics, Metric.TEMPERATURE, TEMPERATURE_FACTOR, -32_767, 32_767, S16_NOT_AVAILABLE));
        putU16be(p, 3, encode(metrics, Metric.HUMIDITY, HUMIDITY_FACTOR, 0, 65_534, U16_NOT_AVAILABLE));
        Number pressure = metrics.get(Metric.PRESSURE.key());
        putU16be(p, 5, pressure == null ? U16_NOT_AVAILABLE
                : (int) unscaled(pressure, HPA, PRESSURE_OFFSET_PA, PRESSURE_OFFSET_PA + 65_534L) - PRESSURE_OFFSET_PA);
        putU16be(p, 7, encode(metrics, Metric.ACCELERATION_X, MILLI, -32_767, 32_767, S16_NOT_AVAILABLE));
        putU16be(p, 9, encode(metrics, Metric.ACCELERATION_Y, MILLI, -32_767, 32_767, S16_NOT_AVAILABLE));
        putU16be(p, 11, encode(metrics, Metric.ACCELERATION_Z, MILLI, -32_767, 32_767, S16_NOT_AVAILABLE));
        Number voltage = metrics.get(Metric.BATTERY_VOLTAGE.key());
        int voltageBits = voltage == null ? VOLTAGE_NOT_AVAILABLE
                : (int) unscaled(voltage, MILLI, BATTERY_OFFSET_MV, BATTERY_OFFSET_MV + 2_046L) - BATTERY_OFFSET_MV;
        Number tx = metrics.get(Metric.TX_POWER.key());
        int txBits = tx == null ? TX_NOT_AVAILABLE
                : (int) Math.max(0, Math.min(30, Math.floorDiv(Math.round(tx.doubleValue()) + 40, 2)));
        putU16be(p, 13, (voltageBits << 5) | txBits);
        Number movement = metrics.get(Metric.MOVEMENT_COUNTER.key());
        p[15] = (byte) (movement == null ? MOVEMENT_NOT_AVAILABLE : Math.floorMod(movement.intValue(), 255));
        Number sequence = metrics.get(Metric.MEASUREMENT_SEQUENCE.key());
        putU16be(p, 16, sequence == null ? U16_NOT_AVAILABLE : (int) Math.floorMod(sequence.longValue(), 65_535L));
        String mac = attributes.get("mac");
        byte[] macBytes = mac == null ? new byte[] {-1, -1, -1, -1, -1, -1} : MAC.parseHex(mac);
        if (macBytes.length != 6) {
            throw new IllegalArgumentException("mac must have 6 bytes: " + mac);
        }
        System.arraycopy(macBytes, 0, p, 18, 6);
        return p;
    }

    private static int encode(Map<String, ? extends Number> metrics, Metric metric, BigDecimal factor,
                              long min, long max, int notAvailable) {
        Number value = metrics.get(metric.key());
        return value == null ? notAvailable : (int) unscaled(value, factor, min, max);
    }
}
