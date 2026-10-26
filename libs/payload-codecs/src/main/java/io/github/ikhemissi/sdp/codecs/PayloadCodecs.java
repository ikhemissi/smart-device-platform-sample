package io.github.ikhemissi.sdp.codecs;

import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Entry point: detects the payload format from its leading byte only (ADR-0003) and delegates to the matching
 * codec. Decoding never throws; any unexpected failure is reported as {@link RejectionReason#MALFORMED_PAYLOAD}.
 */
public final class PayloadCodecs {

    private static final Map<PayloadFormat, PayloadCodec> CODECS = new EnumMap<>(PayloadFormat.class);

    static {
        register(new RuuviDf5Codec());
        register(new RuuviDf3Codec());
        register(new BtHomeV2Codec());
        register(new CayenneLppCodec());
    }

    private PayloadCodecs() {
    }

    private static void register(PayloadCodec codec) {
        CODECS.put(codec.format(), codec);
    }

    public static PayloadCodec forFormat(PayloadFormat format) {
        return CODECS.get(format);
    }

    public static DecodeResult decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new DecodeResult.Rejected(RejectionReason.EMPTY_PAYLOAD, "payload has no bytes");
        }
        int leading = payload[0] & 0xFF;
        PayloadFormat format = switch (leading) {
            case RuuviDf5Codec.FORMAT_BYTE -> PayloadFormat.RUUVI_DF5;
            case RuuviDf3Codec.FORMAT_BYTE -> PayloadFormat.RUUVI_DF3;
            case BtHomeV2Codec.DEVICE_INFO, BtHomeV2Codec.DEVICE_INFO_TRIGGER -> PayloadFormat.BTHOME_V2;
            case CayenneLppCodec.FORMAT_MARKER -> PayloadFormat.CAYENNE_LPP;
            default -> null;
        };
        if (format == null) {
            if (leading == BtHomeV2Codec.DEVICE_INFO_ENCRYPTED || leading == BtHomeV2Codec.DEVICE_INFO_TRIGGER_ENCRYPTED) {
                return new DecodeResult.Rejected(RejectionReason.UNSUPPORTED_FORMAT, "encrypted BTHome v2 payloads are not supported");
            }
            return new DecodeResult.Rejected(RejectionReason.UNKNOWN_FORMAT,
                    "unknown leading byte 0x" + HexFormat.of().withUpperCase().toHexDigits((byte) leading));
        }
        try {
            return CODECS.get(format).decode(payload);
        } catch (RuntimeException e) {
            return Codecs.malformed(format.id() + ": " + e.getMessage());
        }
    }
}
