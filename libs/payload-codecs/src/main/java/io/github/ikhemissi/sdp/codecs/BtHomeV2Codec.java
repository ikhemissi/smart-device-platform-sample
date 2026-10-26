package io.github.ikhemissi.sdp.codecs;

import static io.github.ikhemissi.sdp.codecs.Codecs.putLe;
import static io.github.ikhemissi.sdp.codecs.Codecs.sLe;
import static io.github.ikhemissi.sdp.codecs.Codecs.scaled;
import static io.github.ikhemissi.sdp.codecs.Codecs.uLe;
import static io.github.ikhemissi.sdp.codecs.Codecs.unscaled;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BTHome v2, unencrypted: https://bthome.io/format/. The payload starts at the device-information byte (the
 * 0xFCD2 service UUID is stripped by the sender). Values are little-endian. As mandated by BTHome, parsing stops
 * at the first unsupported object id.
 */
final class BtHomeV2Codec implements PayloadCodec {

    static final int DEVICE_INFO = 0x40;
    static final int DEVICE_INFO_TRIGGER = 0x44;
    static final int DEVICE_INFO_ENCRYPTED = 0x41;
    static final int DEVICE_INFO_TRIGGER_ENCRYPTED = 0x45;

    private record ObjectType(int id, Metric metric, int length, boolean signed, BigDecimal factor) {
    }

    private static final List<ObjectType> TYPES = List.of(
            new ObjectType(0x00, Metric.PACKET_ID, 1, false, BigDecimal.ONE),
            new ObjectType(0x01, Metric.BATTERY_LEVEL, 1, false, BigDecimal.ONE),
            new ObjectType(0x02, Metric.TEMPERATURE, 2, true, new BigDecimal("0.01")),
            new ObjectType(0x03, Metric.HUMIDITY, 2, false, new BigDecimal("0.01")),
            new ObjectType(0x04, Metric.PRESSURE, 3, false, new BigDecimal("0.01")),
            new ObjectType(0x05, Metric.ILLUMINANCE, 3, false, new BigDecimal("0.01")),
            new ObjectType(0x0C, Metric.BATTERY_VOLTAGE, 2, false, new BigDecimal("0.001")),
            new ObjectType(0x12, Metric.CO2, 2, false, BigDecimal.ONE),
            new ObjectType(0x2E, Metric.HUMIDITY, 1, false, BigDecimal.ONE),
            new ObjectType(0x45, Metric.TEMPERATURE, 2, true, new BigDecimal("0.1")));

    /** Object types used when encoding, in ascending object id order as required by BTHome. */
    private static final List<ObjectType> ENCODING_TYPES = TYPES.stream()
            .filter(t -> t.id() != 0x2E && t.id() != 0x45)
            .toList();

    private static final Set<Metric> SUPPORTED = EnumSet.copyOf(ENCODING_TYPES.stream().map(ObjectType::metric).toList());

    @Override
    public PayloadFormat format() {
        return PayloadFormat.BTHOME_V2;
    }

    @Override
    public Set<Metric> supportedMetrics() {
        return SUPPORTED;
    }

    @Override
    public DecodeResult decode(byte[] p) {
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        Map<String, String> attributes = new LinkedHashMap<>();
        int offset = 1;
        while (offset < p.length) {
            int id = p[offset] & 0xFF;
            ObjectType type = TYPES.stream().filter(t -> t.id() == id).findFirst().orElse(null);
            if (type == null) {
                String objectId = "0x" + HexFormat.of().withUpperCase().toHexDigits((byte) id);
                if (metrics.isEmpty()) {
                    return new DecodeResult.Rejected(RejectionReason.NO_SUPPORTED_DATA, "unsupported BTHome object id " + objectId);
                }
                attributes.put("decode_warning", "UNSUPPORTED_OBJECT_ID:" + objectId);
                break;
            }
            if (offset + 1 + type.length() > p.length) {
                return Codecs.malformed("truncated BTHome object 0x" + HexFormat.of().withUpperCase().toHexDigits((byte) id));
            }
            long raw = type.signed() ? sLe(p, offset + 1, type.length()) : uLe(p, offset + 1, type.length());
            metrics.put(uniqueKey(metrics, type.metric().key()), scaled(raw, type.factor()));
            offset += 1 + type.length();
        }
        if (metrics.isEmpty()) {
            return new DecodeResult.Rejected(RejectionReason.NO_SUPPORTED_DATA, "BTHome payload contains no measurement");
        }
        return new DecodeResult.Decoded(PayloadFormat.BTHOME_V2, metrics, attributes);
    }

    /** A metric repeated in one payload (allowed by BTHome) is keyed {@code <key>_2}, {@code <key>_3}, ... */
    private static String uniqueKey(Map<String, BigDecimal> metrics, String key) {
        String candidate = key;
        for (int n = 2; metrics.containsKey(candidate); n++) {
            candidate = key + "_" + n;
        }
        return candidate;
    }

    @Override
    public byte[] encode(Map<String, ? extends Number> metrics, Map<String, String> attributes) {
        Codecs.requireSupported(metrics, SUPPORTED, format());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(DEVICE_INFO);
        for (ObjectType type : ENCODING_TYPES) {
            Number value = metrics.get(type.metric().key());
            if (value == null) {
                continue;
            }
            long bits = 8L * type.length();
            long min = type.signed() ? -(1L << (bits - 1)) : 0;
            long max = type.signed() ? (1L << (bits - 1)) - 1 : (1L << bits) - 1;
            byte[] object = new byte[1 + type.length()];
            object[0] = (byte) type.id();
            putLe(object, 1, type.length(), unscaled(value, type.factor(), min, max));
            out.writeBytes(object);
        }
        return out.toByteArray();
    }
}
