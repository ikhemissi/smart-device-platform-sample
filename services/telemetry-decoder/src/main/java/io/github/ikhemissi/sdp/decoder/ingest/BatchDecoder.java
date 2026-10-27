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

        for (TelemetryMessage m : messages) {
            String deviceKey = (m.deviceId() == null ? "" : m.deviceId()) + '\n' + m.messageKey();
            if (!seen.add(deviceKey)) {
                duplicates++;
                continue;
            }
            if (m.deviceId() == null || m.deviceId().isBlank()) {
                rejections.add(rejection(m, receivedAt, MISSING_DEVICE, "message has no IoT Hub device id"));
                outcomes.add(new Outcome(m, null, MISSING_DEVICE));
                continue;
            }
            DecodeResult result = PayloadCodecs.decode(m.payload());
            if (result instanceof DecodeResult.Rejected r) {
                rejections.add(rejection(m, receivedAt, r.reason().name(), r.detail()));
                outcomes.add(new Outcome(m, null, r.reason().name()));
                continue;
            }
            DecodeResult.Decoded d = (DecodeResult.Decoded) result;
            String siteProblem = siteProblem(m.siteId());
            if (siteProblem != null) {
                rejections.add(rejection(m, receivedAt, MISSING_SITE, siteProblem));
                outcomes.add(new Outcome(m, d.format().id(), MISSING_SITE));
                continue;
            }
            measurements.add(new Measurement(m.deviceId(), m.siteId(), m.enqueuedAt(), receivedAt, m.messageKey(),
                    d.format().id(), m.payload(), d.metrics(), d.attributes()));
            outcomes.add(new Outcome(m, d.format().id(), null));
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

    private static Rejection rejection(TelemetryMessage m, Instant receivedAt, String reason, String detail) {
        String siteId = m.siteId() != null && SITE_ID.matcher(m.siteId()).matches() ? m.siteId() : null;
        return new Rejection(m.deviceId(), siteId, m.enqueuedAt(), receivedAt, m.messageKey(), reason, detail, m.payload());
    }
}
