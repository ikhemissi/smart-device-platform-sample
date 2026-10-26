package io.github.ikhemissi.sdp.codecs;

import static io.github.ikhemissi.sdp.codecs.Codecs.putS24be;
import static io.github.ikhemissi.sdp.codecs.Codecs.putU16be;
import static io.github.ikhemissi.sdp.codecs.Codecs.s16be;
import static io.github.ikhemissi.sdp.codecs.Codecs.s24be;
import static io.github.ikhemissi.sdp.codecs.Codecs.scaled;
import static io.github.ikhemissi.sdp.codecs.Codecs.u16be;
import static io.github.ikhemissi.sdp.codecs.Codecs.u8;
import static io.github.ikhemissi.sdp.codecs.Codecs.unscaled;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cayenne Low Power Payload (https://docs.mydevices.com/docs/lorawan/cayenne-lpp) prefixed with the 0xCA format
 * marker (ADR-0003). Values are big-endian. A metric reported on several channels is keyed {@code <metric>_ch<n>}.
 */
final class CayenneLppCodec implements PayloadCodec {

    static final int FORMAT_MARKER = 0xCA;

    private static final int DIGITAL_INPUT = 0x00;
    private static final int DIGITAL_OUTPUT = 0x01;
    private static final int ANALOG_INPUT = 0x02;
    private static final int ANALOG_OUTPUT = 0x03;
    private static final int ILLUMINANCE = 0x65;
    private static final int PRESENCE = 0x66;
    private static final int TEMPERATURE = 0x67;
    private static final int HUMIDITY = 0x68;
    private static final int ACCELEROMETER = 0x71;
    private static final int BAROMETER = 0x73;
    private static final int GPS = 0x88;

    private static final BigDecimal TENTH = new BigDecimal("0.1");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal HUNDREDTH = new BigDecimal("0.01");
    private static final BigDecimal MILLI = new BigDecimal("0.001");
    private static final BigDecimal TEN_THOUSANDTH = new BigDecimal("0.0001");
    private static final Pattern CHANNEL_KEY = Pattern.compile("^(.+?)(?:_ch(\\d{1,3}))?$");
    private static final int DEFAULT_CHANNEL = 1;

    private static final Map<Integer, Integer> VALUE_LENGTH = Map.ofEntries(
            Map.entry(DIGITAL_INPUT, 1), Map.entry(DIGITAL_OUTPUT, 1), Map.entry(ANALOG_INPUT, 2),
            Map.entry(ANALOG_OUTPUT, 2), Map.entry(ILLUMINANCE, 2), Map.entry(PRESENCE, 1),
            Map.entry(TEMPERATURE, 2), Map.entry(HUMIDITY, 1), Map.entry(ACCELEROMETER, 6),
            Map.entry(BAROMETER, 2), Map.entry(GPS, 9));

    private static final Set<Metric> SUPPORTED = EnumSet.of(Metric.DIGITAL_INPUT, Metric.DIGITAL_OUTPUT,
            Metric.ANALOG_INPUT, Metric.ANALOG_OUTPUT, Metric.ILLUMINANCE, Metric.PRESENCE, Metric.TEMPERATURE,
            Metric.HUMIDITY, Metric.ACCELERATION_X, Metric.ACCELERATION_Y, Metric.ACCELERATION_Z, Metric.PRESSURE,
            Metric.LATITUDE, Metric.LONGITUDE, Metric.ALTITUDE);

    private record Reading(Metric metric, int channel, BigDecimal value) {
    }

    @Override
    public PayloadFormat format() {
        return PayloadFormat.CAYENNE_LPP;
    }

    @Override
    public Set<Metric> supportedMetrics() {
        return SUPPORTED;
    }

    @Override
    public DecodeResult decode(byte[] p) {
        if (p.length == 1) {
            return new DecodeResult.Rejected(RejectionReason.NO_SUPPORTED_DATA, "Cayenne LPP frame is empty");
        }
        List<Reading> readings = new ArrayList<>();
        int offset = 1;
        while (offset < p.length) {
            if (offset + 2 > p.length) {
                return Codecs.malformed("truncated Cayenne LPP header at byte " + offset);
            }
            int channel = u8(p, offset);
            int type = u8(p, offset + 1);
            Integer length = VALUE_LENGTH.get(type);
            if (length == null) {
                return Codecs.malformed("unsupported Cayenne LPP type 0x" + HexFormat.of().withUpperCase().toHexDigits((byte) type)
                        + " on channel " + channel);
            }
            int value = offset + 2;
            if (value + length > p.length) {
                return Codecs.malformed("truncated Cayenne LPP value on channel " + channel);
            }
            readings.addAll(read(p, value, channel, type));
            offset = value + length;
        }
        return new DecodeResult.Decoded(PayloadFormat.CAYENNE_LPP, keyed(readings), Map.of());
    }

    private static List<Reading> read(byte[] p, int o, int channel, int type) {
        return switch (type) {
            case DIGITAL_INPUT -> List.of(new Reading(Metric.DIGITAL_INPUT, channel, BigDecimal.valueOf(u8(p, o))));
            case DIGITAL_OUTPUT -> List.of(new Reading(Metric.DIGITAL_OUTPUT, channel, BigDecimal.valueOf(u8(p, o))));
            case ANALOG_INPUT -> List.of(new Reading(Metric.ANALOG_INPUT, channel, scaled(s16be(p, o), HUNDREDTH)));
            case ANALOG_OUTPUT -> List.of(new Reading(Metric.ANALOG_OUTPUT, channel, scaled(s16be(p, o), HUNDREDTH)));
            case ILLUMINANCE -> List.of(new Reading(Metric.ILLUMINANCE, channel, BigDecimal.valueOf(u16be(p, o))));
            case PRESENCE -> List.of(new Reading(Metric.PRESENCE, channel, BigDecimal.valueOf(u8(p, o))));
            case TEMPERATURE -> List.of(new Reading(Metric.TEMPERATURE, channel, scaled(s16be(p, o), TENTH)));
            case HUMIDITY -> List.of(new Reading(Metric.HUMIDITY, channel, scaled(u8(p, o), HALF)));
            case ACCELEROMETER -> List.of(
                    new Reading(Metric.ACCELERATION_X, channel, scaled(s16be(p, o), MILLI)),
                    new Reading(Metric.ACCELERATION_Y, channel, scaled(s16be(p, o + 2), MILLI)),
                    new Reading(Metric.ACCELERATION_Z, channel, scaled(s16be(p, o + 4), MILLI)));
            case BAROMETER -> List.of(new Reading(Metric.PRESSURE, channel, scaled(u16be(p, o), TENTH)));
            case GPS -> List.of(
                    new Reading(Metric.LATITUDE, channel, scaled(s24be(p, o), TEN_THOUSANDTH)),
                    new Reading(Metric.LONGITUDE, channel, scaled(s24be(p, o + 3), TEN_THOUSANDTH)),
                    new Reading(Metric.ALTITUDE, channel, scaled(s24be(p, o + 6), HUNDREDTH)));
            default -> throw new IllegalStateException("unreachable type " + type);
        };
    }

    private static Map<String, BigDecimal> keyed(List<Reading> readings) {
        Map<Metric, Set<Integer>> channels = new HashMap<>();
        readings.forEach(r -> channels.computeIfAbsent(r.metric(), k -> new HashSet<>()).add(r.channel()));
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        for (Reading r : readings) {
            String key = channels.get(r.metric()).size() > 1 ? r.metric().key() + "_ch" + r.channel() : r.metric().key();
            metrics.put(key, r.value());
        }
        return metrics;
    }

    @Override
    public byte[] encode(Map<String, ? extends Number> metrics, Map<String, String> attributes) {
        Codecs.requireSupported(metrics, SUPPORTED, format());
        // channel -> metric -> value, in channel order for a deterministic frame
        Map<Integer, Map<Metric, Number>> byChannel = new TreeMap<>();
        metrics.forEach((key, value) -> {
            Matcher matcher = CHANNEL_KEY.matcher(key);
            matcher.matches();
            int channel = matcher.group(2) == null ? DEFAULT_CHANNEL : Integer.parseInt(matcher.group(2));
            if (channel > 255) {
                throw new IllegalArgumentException("Cayenne LPP channel must be <= 255: " + key);
            }
            byChannel.computeIfAbsent(channel, c -> new LinkedHashMap<>()).put(Metric.fromKey(key).orElseThrow(), value);
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(FORMAT_MARKER);
        byChannel.forEach((channel, values) -> writeChannel(out, channel, values));
        return out.toByteArray();
    }

    private static final Set<Metric> ACCELERATION = EnumSet.of(Metric.ACCELERATION_X, Metric.ACCELERATION_Y, Metric.ACCELERATION_Z);
    private static final Set<Metric> POSITION = EnumSet.of(Metric.LATITUDE, Metric.LONGITUDE, Metric.ALTITUDE);

    private static void writeChannel(ByteArrayOutputStream out, int channel, Map<Metric, Number> values) {
        boolean accelerationWritten = false;
        boolean positionWritten = false;
        for (Map.Entry<Metric, Number> e : values.entrySet()) {
            Number v = e.getValue();
            Metric metric = e.getKey();
            if (ACCELERATION.contains(metric)) {
                if (!accelerationWritten) {
                    byte[] value = new byte[6];
                    putU16be(value, 0, (int) unscaled(values.getOrDefault(Metric.ACCELERATION_X, 0), MILLI, -32_768, 32_767));
                    putU16be(value, 2, (int) unscaled(values.getOrDefault(Metric.ACCELERATION_Y, 0), MILLI, -32_768, 32_767));
                    putU16be(value, 4, (int) unscaled(values.getOrDefault(Metric.ACCELERATION_Z, 0), MILLI, -32_768, 32_767));
                    frame(out, channel, ACCELEROMETER, value);
                    accelerationWritten = true;
                }
                continue;
            }
            if (POSITION.contains(metric)) {
                if (!positionWritten) {
                    byte[] value = new byte[9];
                    putS24be(value, 0, (int) unscaled(values.getOrDefault(Metric.LATITUDE, 0), TEN_THOUSANDTH, -900_000, 900_000));
                    putS24be(value, 3, (int) unscaled(values.getOrDefault(Metric.LONGITUDE, 0), TEN_THOUSANDTH, -1_800_000, 1_800_000));
                    putS24be(value, 6, (int) unscaled(values.getOrDefault(Metric.ALTITUDE, 0), HUNDREDTH, -8_388_608, 8_388_607));
                    frame(out, channel, GPS, value);
                    positionWritten = true;
                }
                continue;
            }
            switch (metric) {
                case DIGITAL_INPUT -> frame(out, channel, DIGITAL_INPUT, oneByte(unscaled(v, BigDecimal.ONE, 0, 255)));
                case DIGITAL_OUTPUT -> frame(out, channel, DIGITAL_OUTPUT, oneByte(unscaled(v, BigDecimal.ONE, 0, 255)));
                case ANALOG_INPUT -> frame(out, channel, ANALOG_INPUT, u16(unscaled(v, HUNDREDTH, -32_768, 32_767)));
                case ANALOG_OUTPUT -> frame(out, channel, ANALOG_OUTPUT, u16(unscaled(v, HUNDREDTH, -32_768, 32_767)));
                case ILLUMINANCE -> frame(out, channel, ILLUMINANCE, u16(unscaled(v, BigDecimal.ONE, 0, 65_535)));
                case PRESENCE -> frame(out, channel, PRESENCE, oneByte(unscaled(v, BigDecimal.ONE, 0, 255)));
                case TEMPERATURE -> frame(out, channel, TEMPERATURE, u16(unscaled(v, TENTH, -32_768, 32_767)));
                case HUMIDITY -> frame(out, channel, HUMIDITY, oneByte(unscaled(v, HALF, 0, 255)));
                case PRESSURE -> frame(out, channel, BAROMETER, u16(unscaled(v, TENTH, 0, 65_535)));
                default -> throw new IllegalArgumentException("Metric not supported by cayenne-lpp: " + metric.key());
            }
        }
    }

    private static byte[] oneByte(long value) {
        return new byte[] {(byte) value};
    }

    private static byte[] u16(long value) {
        byte[] b = new byte[2];
        putU16be(b, 0, (int) value);
        return b;
    }

    private static void frame(ByteArrayOutputStream out, int channel, int type, byte[] value) {
        out.write(channel);
        out.write(type);
        out.writeBytes(value);
    }
}
