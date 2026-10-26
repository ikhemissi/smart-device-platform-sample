package io.github.ikhemissi.sdp.codecs;

/** Reasons why a payload cannot be decoded. */
public enum RejectionReason {
    /** The payload has no bytes. */
    EMPTY_PAYLOAD,
    /** The leading byte does not match any registered format. */
    UNKNOWN_FORMAT,
    /** The leading byte identifies a known but unsupported variant (e.g. encrypted BTHome). */
    UNSUPPORTED_FORMAT,
    /** The payload does not respect its format's structure (length, truncated value, unknown type). */
    MALFORMED_PAYLOAD,
    /** The payload is well-formed but contains no supported measurement. */
    NO_SUPPORTED_DATA
}
