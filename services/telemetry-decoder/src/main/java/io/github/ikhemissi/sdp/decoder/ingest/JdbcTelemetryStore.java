package io.github.ikhemissi.sdp.decoder.ingest;

import io.github.ikhemissi.sdp.decoder.ingest.DecodedBatch.Measurement;
import io.github.ikhemissi.sdp.decoder.ingest.DecodedBatch.Rejection;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL implementation (ADR-0004, M1 design D3/D4). In one transaction: create unknown sites, record message
 * keys in the deduplication ledger, insert only the measurements/rejections whose key is new, then register or
 * update devices. Sites and devices are written in sorted order to avoid deadlocks between decoder replicas.
 */
@Repository
public class JdbcTelemetryStore implements TelemetryStore {

    private static final String INSERT_SITES = """
            INSERT INTO sdp.sites (site_id, display_name)
            SELECT s, s FROM unnest(?::text[]) AS s
            ON CONFLICT (site_id) DO NOTHING
            """;
    private static final String INSERT_LEDGER = """
            INSERT INTO sdp.ingested_messages (device_id, message_key)
            SELECT * FROM unnest(?::text[], ?::text[])
            ON CONFLICT DO NOTHING
            RETURNING device_id, message_key
            """;
    private static final String INSERT_MEASUREMENT = """
            INSERT INTO sdp.measurements
                (device_id, site_id, measured_at, received_at, message_key, payload_format, raw_payload, metrics, attributes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT DO NOTHING
            """;
    private static final String INSERT_REJECTION = """
            INSERT INTO sdp.rejected_messages
                (received_at, enqueued_at, device_id, site_id, message_key, reason, detail, raw_payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPSERT_DEVICE = """
            INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (device_id) DO UPDATE
               SET last_seen_at = GREATEST(sdp.devices.last_seen_at, EXCLUDED.last_seen_at),
                   last_payload_format = CASE WHEN EXCLUDED.last_seen_at >= sdp.devices.last_seen_at
                                              THEN EXCLUDED.last_payload_format
                                              ELSE sdp.devices.last_payload_format END
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcTelemetryStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public StoreResult persist(DecodedBatch batch) {
        return tx.execute(status -> persistInTransaction(batch));
    }

    private StoreResult persistInTransaction(DecodedBatch batch) {
        Set<String> sites = new TreeSet<>();
        batch.measurements().forEach(m -> sites.add(m.siteId()));
        if (!sites.isEmpty()) {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(INSERT_SITES);
                ps.setArray(1, textArray(con, sites));
                return ps;
            });
        }

        Set<String> newKeys = recordInLedger(batch);
        Set<String> duplicateKeys = new HashSet<>();

        List<Measurement> measurements = new ArrayList<>();
        for (Measurement m : batch.measurements()) {
            if (newKeys.contains(TelemetryStore.key(m.deviceId(), m.messageKey()))) {
                measurements.add(m);
            } else {
                duplicateKeys.add(TelemetryStore.key(m.deviceId(), m.messageKey()));
            }
        }
        List<Rejection> rejections = new ArrayList<>();
        for (Rejection r : batch.rejections()) {
            if (newKeys.contains(TelemetryStore.key(r.ledgerDeviceId(), r.messageKey()))) {
                rejections.add(r);
            } else {
                duplicateKeys.add(TelemetryStore.key(r.ledgerDeviceId(), r.messageKey()));
            }
        }

        if (!measurements.isEmpty()) {
            jdbc.batchUpdate(INSERT_MEASUREMENT, measurements, measurements.size(), (ps, m) -> {
                ps.setString(1, m.deviceId());
                ps.setString(2, m.siteId());
                ps.setTimestamp(3, Timestamp.from(m.measuredAt()));
                ps.setTimestamp(4, Timestamp.from(m.receivedAt()));
                ps.setString(5, m.messageKey());
                ps.setString(6, m.payloadFormat());
                ps.setBytes(7, m.rawPayload());
                ps.setString(8, Json.numbers(m.metrics()));
                ps.setString(9, Json.strings(m.attributes()));
            });
            upsertDevices(measurements);
        }
        if (!rejections.isEmpty()) {
            jdbc.batchUpdate(INSERT_REJECTION, rejections, rejections.size(), (ps, r) -> {
                ps.setTimestamp(1, Timestamp.from(r.receivedAt()));
                ps.setTimestamp(2, r.enqueuedAt() == null ? null : Timestamp.from(r.enqueuedAt()));
                ps.setString(3, r.deviceId());
                ps.setString(4, r.siteId());
                ps.setString(5, r.messageKey());
                ps.setString(6, r.reason());
                ps.setString(7, r.detail());
                ps.setBytes(8, r.rawPayload());
            });
        }
        return new StoreResult(new PersistResult(measurements.size(), rejections.size(),
                duplicateKeys.size() + batch.inBatchDuplicates()), duplicateKeys);
    }

    private Set<String> recordInLedger(DecodedBatch batch) {
        List<String> devices = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        batch.measurements().forEach(m -> {
            devices.add(m.deviceId());
            keys.add(m.messageKey());
        });
        batch.rejections().forEach(r -> {
            devices.add(r.ledgerDeviceId());
            keys.add(r.messageKey());
        });
        if (keys.isEmpty()) {
            return Set.of();
        }
        Set<String> inserted = new HashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_LEDGER);
            ps.setArray(1, textArray(con, devices));
            ps.setArray(2, textArray(con, keys));
            return ps;
        }, rs -> {
            inserted.add(TelemetryStore.key(rs.getString(1), rs.getString(2)));
        });
        return inserted;
    }

    private void upsertDevices(List<Measurement> measurements) {
        record DeviceUpdate(String deviceId, String siteId, String format, Instant firstSeen, Instant lastSeen) {
        }
        Map<String, DeviceUpdate> updates = new LinkedHashMap<>();
        for (Measurement m : measurements) {
            updates.merge(m.deviceId(),
                    new DeviceUpdate(m.deviceId(), m.siteId(), m.payloadFormat(), m.measuredAt(), m.measuredAt()),
                    (a, b) -> new DeviceUpdate(a.deviceId(), a.siteId(),
                            b.lastSeen().isBefore(a.lastSeen()) ? a.format() : b.format(),
                            a.firstSeen().isBefore(b.firstSeen()) ? a.firstSeen() : b.firstSeen(),
                            a.lastSeen().isAfter(b.lastSeen()) ? a.lastSeen() : b.lastSeen()));
        }
        List<DeviceUpdate> sorted = updates.values().stream().sorted(Comparator.comparing(DeviceUpdate::deviceId)).toList();
        jdbc.batchUpdate(UPSERT_DEVICE, sorted, sorted.size(), (ps, d) -> {
            ps.setString(1, d.deviceId());
            ps.setString(2, d.siteId());
            ps.setString(3, d.format());
            ps.setTimestamp(4, Timestamp.from(d.firstSeen()));
            ps.setTimestamp(5, Timestamp.from(d.lastSeen()));
        });
    }

    private static Array textArray(Connection con, java.util.Collection<String> values) throws SQLException {
        return con.createArrayOf("text", values.toArray(String[]::new));
    }
}
