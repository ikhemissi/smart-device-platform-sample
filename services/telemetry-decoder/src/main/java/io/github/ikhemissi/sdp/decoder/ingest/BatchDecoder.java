package io.github.ikhemissi.sdp.decoder.ingest;

import io.github.ikhemissi.sdp.codecs.DecodeResult;
import io.github.ikhemissi.sdp.codecs.PayloadCodecs;
import io.github.ikhemissi.sdp.decoder.ingest.DecodedBatch.Measurement;
import io.github.ikhemissi.sdp.decoder.ingest.DecodedBatch.Outcome;
import io.github.ikhemissi.sdp.decoder.ingest.DecodedBatch.Rejection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decodes a batch of messages and classifies each one as a measurement or a rejection (spec telemetry-ingestion:
 * "Quarantine of undecodable messages"). Pure and stateless.
 */
public final class BatchDecoder {

    /** Same rule as the {@code sdp.sites} check constraint, so a bad site id can never poison a batch. */
    private static final Pattern SITE_ID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    public static final String MISSING_DEVICE = "MISSING_DEVICE";
    public static final String MISSING_SITE = "MISSING_SITE";

    private BatchDecoder() {
    }

    public static DecodedBatch decode(List<TelemetryMessage> messages, Instant receivedAt) {
        List<Measurement> measurements = new ArrayList<>();
        List<Rejection> rejections = new ArrayList<>();
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int duplicates = 0;

        for (TelemetryMessage message : messages) {
            if (!seen.add(TelemetryStore.key(message.deviceId(), message.messageKey()))) {
                duplicates++;
                continue;
            }
            if (message.deviceId() == null || message.deviceId().isBlank()) {
                rejections.add(rejection(message, receivedAt, MISSING_DEVICE, "message has no IoT Hub device id"));
                outcomes.add(new Outcome(message, null, MISSING_DEVICE));
                continue;
            }
            DecodeResult result = PayloadCodecs.decode(message.payload());
            if (result instanceof DecodeResult.Rejected rejected) {
                rejections.add(rejection(message, receivedAt, rejected.reason().name(), rejected.detail()));
                outcomes.add(new Outcome(message, null, rejected.reason().name()));
                continue;
            }
            DecodeResult.Decoded decoded = (DecodeResult.Decoded) result;
            String format = decoded.format().id();
            String siteProblem = siteProblem(message.siteId());
            if (siteProblem != null) {
                rejections.add(rejection(message, receivedAt, MISSING_SITE, siteProblem));
                outcomes.add(new Outcome(message, format, MISSING_SITE));
                continue;
            }
            measurements.add(new Measurement(message.deviceId(), message.siteId(), message.enqueuedAt(), receivedAt,
                    message.messageKey(), format, message.payload(), decoded.metrics(), decoded.attributes()));
            outcomes.add(new Outcome(message, format, null));
        }
        return new DecodedBatch(measurements, rejections, outcomes, duplicates);
    }

    private static String siteProblem(String siteId) {
        if (siteId == null || siteId.isBlank()) {
            return "message has no siteId property (device twin tag missing?)";
        }
        if (siteId.length() < 3 || siteId.length() > 40 || !SITE_ID.matcher(siteId).matches()) {
            return "invalid siteId '" + siteId + "'";
        }
        return null;
    }

    private static Rejection rejection(TelemetryMessage message, Instant receivedAt, String reason, String detail) {
        String siteId = message.siteId() != null && SITE_ID.matcher(message.siteId()).matches() ? message.siteId() : null;
        return new Rejection(message.deviceId(), siteId, message.enqueuedAt(), receivedAt, message.messageKey(), reason,
                detail, message.payload());
    }
}
