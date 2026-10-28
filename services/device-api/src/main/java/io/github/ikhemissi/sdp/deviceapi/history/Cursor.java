package io.github.ikhemissi.sdp.deviceapi.history;

import io.github.ikhemissi.sdp.deviceapi.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Opaque keyset position (M3 design D4): base64url of the last row's measurement time and message key. */
record Cursor(Instant measuredAt, String messageKey) {

    String encode() {
        String raw = measuredAt.getEpochSecond() + "." + measuredAt.getNano() + "|" + messageKey;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            String[] time = raw.substring(0, separator).split("\\.");
            return new Cursor(Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1])), raw.substring(separator + 1));
        } catch (RuntimeException e) {
            throw ApiException.invalid(Map.of("cursor", List.of("is not a valid cursor")));
        }
    }
}
