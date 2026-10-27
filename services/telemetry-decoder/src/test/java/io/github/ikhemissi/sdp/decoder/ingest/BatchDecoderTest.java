package io.github.ikhemissi.sdp.decoder.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec telemetry-ingestion: "Measurement content" and "Quarantine of undecodable messages" (decoding side). */
class BatchDecoderTest {

    static final String RUUVI_DF5 = "0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F";
    static final Instant ENQUEUED = Instant.parse("2026-10-27T09:00:00Z");
    static final Instant RECEIVED = Instant.parse("2026-10-27T09:00:02Z");

    static TelemetryMessage message(String deviceId, String siteId, String key, String hex) {
        return new TelemetryMessage(deviceId, siteId, ENQUEUED, key, HexFormat.of().parseHex(hex), null);
    }

    @Test
    void decodableMessageBecomesAMeasurement() {
        DecodedBatch batch = BatchDecoder.decode(List.of(message("paris-01-ruuvi5-001", "paris-01", "k1", RUUVI_DF5)), RECEIVED);

        assertThat(batch.rejections()).isEmpty();
        DecodedBatch.Measurement m = batch.measurements().getFirst();
        assertThat(m.deviceId()).isEqualTo("paris-01-ruuvi5-001");
        assertThat(m.siteId()).isEqualTo("paris-01");
        assertThat(m.payloadFormat()).isEqualTo("ruuvi-df5");
        assertThat(m.measuredAt()).isEqualTo(ENQUEUED);
        assertThat(m.receivedAt()).isEqualTo(RECEIVED);
        assertThat(m.rawPayload()).hasSize(24);
        assertThat(m.metrics().get("temperature")).isEqualByComparingTo("24.3");
        assertThat(m.attributes()).containsEntry("mac", "CB:B8:33:4C:88:4F");
    }

    @Test
    void corruptedMessageIsRejectedWithoutAffectingOthers() {
        List<TelemetryMessage> messages = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            messages.add(message("dev-" + i, "lyon-01", "k" + i, RUUVI_DF5));
        }
        messages.add(message("dev-x", "lyon-01", "bad", "7F0102"));

        DecodedBatch batch = BatchDecoder.decode(messages, RECEIVED);

        assertThat(batch.measurements()).hasSize(99);
        assertThat(batch.rejections()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo("UNKNOWN_FORMAT");
            assertThat(r.deviceId()).isEqualTo("dev-x");
            assertThat(r.rawPayload()).containsExactly(0x7F, 0x01, 0x02);
        });
    }

    @Test
    void decodableMessageWithoutSiteIsRejected() {
        DecodedBatch batch = BatchDecoder.decode(List.of(message("dev", null, "k", RUUVI_DF5)), RECEIVED);

        assertThat(batch.measurements()).isEmpty();
        assertThat(batch.rejections().getFirst().reason()).isEqualTo(BatchDecoder.MISSING_SITE);
        assertThat(batch.outcomes().getFirst().payloadFormat()).isEqualTo("ruuvi-df5");
    }

    @Test
    void invalidSiteIdIsRejectedAsMissingSite() {
        DecodedBatch batch = BatchDecoder.decode(List.of(message("dev", "Paris_01", "k", RUUVI_DF5)), RECEIVED);

        assertThat(batch.rejections().getFirst().reason()).isEqualTo(BatchDecoder.MISSING_SITE);
        assertThat(batch.rejections().getFirst().siteId()).isNull();
    }

    @Test
    void messageWithoutDeviceIsRejected() {
        DecodedBatch batch = BatchDecoder.decode(List.of(message(null, "paris-01", "k", RUUVI_DF5)), RECEIVED);

        assertThat(batch.rejections().getFirst().reason()).isEqualTo(BatchDecoder.MISSING_DEVICE);
        assertThat(batch.rejections().getFirst().ledgerDeviceId()).isEmpty();
    }

    @Test
    void duplicateWithinTheBatchIsKeptOnce() {
        DecodedBatch batch = BatchDecoder.decode(List.of(
                message("dev", "paris-01", "k", RUUVI_DF5),
                message("dev", "paris-01", "k", RUUVI_DF5),
                message("other", "paris-01", "k", RUUVI_DF5)), RECEIVED);

        assertThat(batch.measurements()).hasSize(2);
        assertThat(batch.inBatchDuplicates()).isEqualTo(1);
    }
}
