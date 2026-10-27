package io.github.ikhemissi.sdp.decoder.ingest;

import static io.github.ikhemissi.sdp.decoder.ingest.BatchDecoderTest.RUUVI_DF5;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.decoder.DecoderDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tasks 4.2/4.3 — persistence with the decoder's real database privileges. Spec telemetry-ingestion:
 * "Measurement content", "Idempotent persistence", "Device auto-registration", "Quarantine of undecodable messages".
 */
@SpringBootTest(properties = "sdp.decoder.processor.enabled=false")
class JdbcTelemetryStoreIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        DecoderDatabase.registerDecoderDataSource(registry);
    }

    @Autowired
    TelemetryStore store;

    private final JdbcTemplate admin = DecoderDatabase.admin();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private String device(String name) {
        return "it-" + run + "-" + name;
    }

    private TelemetryMessage message(String deviceId, String site, String key, Instant enqueued, String hex) {
        return new TelemetryMessage(deviceId, site, enqueued, key, HexFormat.of().parseHex(hex), null);
    }

    private TelemetryStore.StoreResult persist(TelemetryMessage... messages) {
        return store.persist(BatchDecoder.decode(List.of(messages), Instant.now()));
    }

    @Test
    void storedMeasurementIsCompleteAndDeviceIsAutoRegistered() {
        String dev = device("ruuvi5-001");

        persist(message(dev, "paris-01", "m1", t0, RUUVI_DF5));

        Map<String, Object> row = admin.queryForMap("""
                SELECT site_id, payload_format, measured_at, raw_payload, metrics->>'temperature' AS temperature,
                       attributes->>'mac' AS mac
                  FROM sdp.measurements WHERE device_id = ?""", dev);
        assertThat(row.get("site_id")).isEqualTo("paris-01");
        assertThat(row.get("payload_format")).isEqualTo("ruuvi-df5");
        assertThat(((Timestamp) row.get("measured_at")).toInstant()).isEqualTo(t0);
        assertThat((byte[]) row.get("raw_payload")).hasSize(24);
        assertThat(row.get("temperature")).isEqualTo("24.3");
        assertThat(row.get("mac")).isEqualTo("CB:B8:33:4C:88:4F");

        Map<String, Object> device = admin.queryForMap(
                "SELECT site_id, last_payload_format, first_seen_at, last_seen_at FROM sdp.devices WHERE device_id = ?", dev);
        assertThat(device.get("site_id")).isEqualTo("paris-01");
        assertThat(device.get("last_payload_format")).isEqualTo("ruuvi-df5");
        assertThat(((Timestamp) device.get("first_seen_at")).toInstant()).isEqualTo(t0);
        assertThat(((Timestamp) device.get("last_seen_at")).toInstant()).isEqualTo(t0);
    }

    @Test
    void subsequentMessagesUpdateLastSeenButNotFirstSeen() {
        String dev = device("bthome-004");
        persist(message(dev, "lyon-01", "m1", t0, "4002C40903BF13"));
        persist(message(dev, "lyon-01", "m2", t0.plusSeconds(30), "4002C40903BF13"));
        persist(message(dev, "lyon-01", "m0", t0.minusSeconds(30), RUUVI_DF5));

        Map<String, Object> device = admin.queryForMap(
                "SELECT last_payload_format, first_seen_at, last_seen_at FROM sdp.devices WHERE device_id = ?", dev);
        assertThat(((Timestamp) device.get("first_seen_at")).toInstant()).isEqualTo(t0);
        assertThat(((Timestamp) device.get("last_seen_at")).toInstant()).isEqualTo(t0.plusSeconds(30));
        assertThat(device.get("last_payload_format")).as("an older message does not change the latest format").isEqualTo("bthome-v2");
    }

    @Test
    void duplicateDeliveryIsStoredOnce() {
        String dev = device("dup");
        TelemetryMessage m = message(dev, "paris-01", "m1", t0, RUUVI_DF5);

        TelemetryStore.StoreResult first = persist(m);
        TelemetryStore.StoreResult second = persist(m);

        assertThat(first.counts()).isEqualTo(new PersistResult(1, 0, 0));
        assertThat(second.counts()).isEqualTo(new PersistResult(0, 0, 1));
        assertThat(second.duplicateKeys()).containsExactly(TelemetryStore.key(dev, "m1"));
        assertThat(count("sdp.measurements", dev)).isEqualTo(1);
    }

    @Test
    void deviceResendingAMessageLaterIsStoredOnceWithTheFirstReceiptTime() {
        String dev = device("resend");
        persist(message(dev, "paris-01", "m1", t0, RUUVI_DF5));
        persist(message(dev, "paris-01", "m1", t0.plus(10, ChronoUnit.MINUTES), RUUVI_DF5));

        assertThat(admin.queryForList("SELECT measured_at FROM sdp.measurements WHERE device_id = ?", Timestamp.class, dev))
                .singleElement().satisfies(ts -> assertThat(ts.toInstant()).isEqualTo(t0));
    }

    @Test
    void sameMessageIdFromTwoDevicesIsStoredTwice() {
        persist(message(device("a"), "paris-01", "same", t0, RUUVI_DF5), message(device("b"), "paris-01", "same", t0, RUUVI_DF5));

        assertThat(count("sdp.measurements", device("a")) + count("sdp.measurements", device("b"))).isEqualTo(2);
    }

    @Test
    void corruptedMessageIsQuarantinedWhileTheRestOfTheBatchIsStored() {
        List<TelemetryMessage> batch = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            batch.add(message(device("batch"), "marseille-01", "m" + i, t0.plusMillis(i), RUUVI_DF5));
        }
        batch.add(message(device("batch"), "marseille-01", "bad", t0, "7F0102"));

        TelemetryStore.StoreResult result = store.persist(BatchDecoder.decode(batch, Instant.now()));

        assertThat(result.counts()).isEqualTo(new PersistResult(99, 1, 0));
        assertThat(count("sdp.measurements", device("batch"))).isEqualTo(99);
        assertThat(admin.queryForMap("SELECT reason, detail, raw_payload FROM sdp.rejected_messages WHERE device_id = ?", device("batch")))
                .containsEntry("reason", "UNKNOWN_FORMAT")
                .satisfies(r -> assertThat((byte[]) r.get("raw_payload")).containsExactly(0x7F, 0x01, 0x02));
    }

    @Test
    void messageWithoutSiteIsStoredAsRejectedAndRedeliveryIsIgnored() {
        String dev = device("nosite");
        TelemetryMessage m = message(dev, null, "m1", t0, RUUVI_DF5);
        persist(m);
        persist(m);

        assertThat(admin.queryForList("SELECT reason FROM sdp.rejected_messages WHERE device_id = ?", String.class, dev))
                .containsExactly("MISSING_SITE");
        assertThat(count("sdp.measurements", dev)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.devices WHERE device_id = ?", Integer.class, dev)).isZero();
    }

    @Test
    void unknownSiteIsCreated() {
        String site = "site-" + run;
        persist(message(device("newsite"), site, "m1", t0, RUUVI_DF5));

        assertThat(admin.queryForObject("SELECT display_name FROM sdp.sites WHERE site_id = ?", String.class, site)).isEqualTo(site);
    }

    private int count(String table, String deviceId) {
        return admin.queryForObject("SELECT count(*) FROM " + table + " WHERE device_id = ?", Integer.class, deviceId);
    }
}
