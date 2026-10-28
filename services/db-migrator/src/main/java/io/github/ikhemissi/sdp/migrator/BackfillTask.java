package io.github.ikhemissi.sdp.migrator;

import io.github.ikhemissi.sdp.codecs.DecodeResult;
import io.github.ikhemissi.sdp.codecs.MetricsJson;
import io.github.ikhemissi.sdp.codecs.PayloadCodecs;
import io.github.ikhemissi.sdp.codecs.PayloadFormat;
import io.github.ikhemissi.sdp.fleet.PayloadFactory;
import io.github.ikhemissi.sdp.fleet.ReadingModel;
import io.github.ikhemissi.sdp.fleet.SimulatedDevice;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Demo history backfill (M3 design D7, opt-in task {@code backfill}). For every known device, generates readings
 * from {@code days} ago up to its first live measurement with the same per-device model as the live simulator, encodes
 * them with the real codecs and stores the decoded result exactly as the decoder does. Each device is loaded in one
 * transaction that also moves its {@code first_seen_at} back, so a second run adds nothing and an interrupted run
 * resumes where it stopped.
 */
@Component
public class BackfillTask {

    private static final Logger log = LoggerFactory.getLogger(BackfillTask.class);
    private static final String COPY = """
            COPY sdp.measurements (device_id, site_id, measured_at, received_at, message_key, payload_format, raw_payload, metrics, attributes)
            FROM STDIN WITH (FORMAT csv)""";
    private static final int FLUSH_BYTES = 1 << 20;

    record Device(String deviceId, String siteId, PayloadFormat format, Instant firstSeenAt) {
    }

    public record Result(int devices, long measurements, long skippedReadings) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MetricRollups rollups;
    private final MigratorProperties.Backfill settings;
    private final Clock clock;

    public BackfillTask(JdbcTemplate jdbc, TransactionTemplate tx, MigratorProperties properties, MetricRollups rollups,
                        Optional<Clock> clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rollups = rollups;
        this.settings = properties.backfill();
        this.clock = clock.orElse(Clock.systemUTC());
    }

    public Result run() {
        Instant from = clock.instant().minus(Duration.ofDays(settings.days()));
        List<Device> devices = devices(from);
        if (devices.isEmpty()) {
            log.info("History backfill: nothing to do (no device first seen after {})", from);
            return new Result(0, 0, 0);
        }
        Instant until = devices.stream().map(Device::firstSeenAt).max(Instant::compareTo).orElseThrow();
        ensurePartitions(from, until);
        long total = 0;
        long skipped = 0;
        for (Device device : devices) {
            long[] counts = tx.execute(status -> backfill(device, from));
            total += counts[0];
            skipped += counts[1];
            log.info("History backfill: {} measurements for {} ({} to {})", counts[0], device.deviceId(), from, device.firstSeenAt());
        }
        jdbc.execute("ANALYZE sdp.measurements");
        // Past hours changed: fleet rollups are rebuilt by the next maintenance run.
        rollups.reset();
        log.info("History backfill completed: {} devices, {} measurements, {} undecodable readings skipped, from {}",
                devices.size(), total, skipped, from);
        return new Result(devices.size(), total, skipped);
    }

    private List<Device> devices(Instant from) {
        String sites = settings.sites().isEmpty() ? "" : " AND site_id = ANY (?)";
        Object[] args = settings.sites().isEmpty()
                ? new Object[] {Timestamp.from(from)}
                : new Object[] {Timestamp.from(from), settings.sites().toArray(String[]::new)};
        return jdbc.query("""
                        SELECT device_id, site_id, last_payload_format, first_seen_at FROM sdp.devices
                         WHERE first_seen_at > ?""" + sites + " ORDER BY device_id",
                (rs, i) -> new Device(rs.getString(1), rs.getString(2), format(rs.getString(3)),
                        rs.getTimestamp(4).toInstant()), args)
                .stream().filter(d -> d.format() != null).toList();
    }

    private static PayloadFormat format(String id) {
        return Arrays.stream(PayloadFormat.values()).filter(f -> f.id().equals(id)).findFirst().orElse(null);
    }

    /** Daily partitions for the backfilled days (as schema owner), so no row lands in the default partition. */
    private void ensurePartitions(Instant from, Instant until) {
        jdbc.queryForList("""
                SELECT partman.create_partition_time('sdp.measurements',
                       ARRAY(SELECT generate_series(date_trunc('day', ?::timestamptz), date_trunc('day', ?::timestamptz), interval '1 day')))""",
                Timestamp.from(from), Timestamp.from(until));
    }

    private long[] backfill(Device device, Instant from) {
        SimulatedDevice simulated = new SimulatedDevice(device.deviceId(), device.siteId(), device.format());
        // Anchored at the first live measurement so slow drifts (battery) join the live readings continuously.
        ReadingModel model = new ReadingModel(simulated, List.of(), device.firstSeenAt());
        Random random = new Random(device.deviceId().hashCode() * 31L + from.getEpochSecond());
        PayloadFactory payloads = new PayloadFactory(0, random.nextLong());
        Grid grid = new Grid(device.deviceId(), settings.interval().toMillis(), settings.jitter().toMillis());
        Map<String, Instant> metricsLastSeen = new TreeMap<>();
        long[] counts = new long[2];
        Instant[] first = new Instant[1];

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            CopyIn copy = connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY);
            try {
                StringBuilder rows = new StringBuilder(FLUSH_BYTES + 4096);
                for (long slot = grid.firstSlot(from); grid.time(slot).isBefore(device.firstSeenAt()); slot++) {
                    Instant at = grid.time(slot);
                    byte[] payload = payloads.build(simulated, model.next(at)).bytes();
                    if (PayloadCodecs.decode(payload) instanceof DecodeResult.Decoded decoded) {
                        appendRow(rows, device, at, at.plusMillis(300 + random.nextInt(1200)), decoded, payload);
                        decoded.metrics().keySet().forEach(metric -> metricsLastSeen.put(metric, at));
                        if (first[0] == null) {
                            first[0] = at;
                        }
                        counts[0]++;
                    } else {
                        counts[1]++;
                    }
                    if (rows.length() >= FLUSH_BYTES) {
                        write(copy, rows);
                    }
                }
                write(copy, rows);
                copy.endCopy();
            } finally {
                if (copy.isActive()) {
                    copy.cancelCopy();
                }
            }
            return null;
        });

        if (first[0] != null) {
            jdbc.update("UPDATE sdp.devices SET first_seen_at = LEAST(first_seen_at, ?) WHERE device_id = ?",
                    Timestamp.from(first[0]), device.deviceId());
            metricsLastSeen.forEach((metric, lastSeen) -> jdbc.update("""
                    INSERT INTO sdp.device_metrics (device_id, metric, last_seen_at) VALUES (?, ?, ?)
                    ON CONFLICT (device_id, metric) DO UPDATE
                       SET last_seen_at = GREATEST(sdp.device_metrics.last_seen_at, EXCLUDED.last_seen_at)""",
                    device.deviceId(), metric, Timestamp.from(lastSeen)));
        }
        return counts;
    }

    /**
     * Reporting instants of a device: slot {@code k} is at {@code k * interval + offset + jitter(k)}, with offset and
     * jitter derived from the device id and slot number. The same instants are produced by every run, so re-running
     * never adds rows next to existing ones (jitter is below half the interval, so instants stay ordered).
     */
    record Grid(long seed, long interval, long jitter, long offset) {

        Grid(String deviceId, long interval, long jitter) {
            this(mix(deviceId.hashCode()), interval, jitter, Math.floorMod(mix(deviceId.hashCode() + 1L), interval));
        }

        Instant time(long slot) {
            long variation = jitter == 0 ? 0 : Math.floorMod(mix(seed ^ slot), 2 * jitter + 1) - jitter;
            return Instant.ofEpochMilli(slot * interval + offset + variation);
        }

        long firstSlot(Instant from) {
            long slot = Math.floorDiv(from.toEpochMilli() - offset - jitter, interval);
            while (time(slot).isBefore(from)) {
                slot++;
            }
            return slot;
        }

        private static long mix(long z) {
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }
    }

    private static void appendRow(StringBuilder rows, Device device, Instant measuredAt, Instant receivedAt,
                                  DecodeResult.Decoded decoded, byte[] payload) {
        csv(rows, device.deviceId()).append(',');
        csv(rows, device.siteId()).append(',');
        rows.append(measuredAt).append(',')
                .append(receivedAt).append(',')
                .append("backfill-").append(measuredAt.toEpochMilli()).append(',')
                .append(decoded.format().id()).append(',')
                .append("\\x").append(HexFormat.of().formatHex(payload)).append(',');
        csv(rows, MetricsJson.numbers(decoded.metrics())).append(',');
        csv(rows, MetricsJson.strings(decoded.attributes())).append('\n');
    }

    private static StringBuilder csv(StringBuilder rows, String value) {
        return rows.append('"').append(value.replace("\"", "\"\"")).append('"');
    }

    private static void write(CopyIn copy, StringBuilder rows) throws java.sql.SQLException {
        if (!rows.isEmpty()) {
            byte[] bytes = rows.toString().getBytes(StandardCharsets.UTF_8);
            copy.writeToCopy(bytes, 0, bytes.length);
            rows.setLength(0);
        }
    }
}
