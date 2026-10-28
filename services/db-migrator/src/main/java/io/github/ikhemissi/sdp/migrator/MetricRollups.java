package io.github.ikhemissi.sdp.migrator;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hourly per-device metric rollups for fleet summaries (M6, spec fleet-analytics). Rolls up, one day per transaction,
 * every UTC hour that ended before the ingestion watermark (minus a margin for late device clocks), so a rolled-up
 * hour can no longer change; the device API reads raw measurements for the rest.
 */
@Component
public class MetricRollups {

    private static final Logger log = LoggerFactory.getLogger(MetricRollups.class);
    static final Duration WATERMARK_MARGIN = Duration.ofMinutes(5);
    private static final Duration BATCH = Duration.ofDays(1);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public MetricRollups(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Rolls up the hours that became complete and purges rollups older than the retention; returns the rows written. */
    public long run(Duration retention) {
        Instant oldest = Instant.now().minus(retention).truncatedTo(ChronoUnit.HOURS);
        long rows = 0;
        Optional<Instant> rollable = rollableUntil();
        if (rollable.isPresent()) {
            Long written;
            while ((written = tx.execute(status -> rollUpNextBatch(oldest, rollable.get()))) != null) {
                rows += written;
            }
        }
        int purged = jdbc.update("DELETE FROM sdp.metric_rollups_hourly WHERE hour < ?", Timestamp.from(oldest));
        log.info("Metric rollups: rows={}, rolledUntil={}, purged={}", rows, rolledUntil().orElse(null), purged);
        return rows;
    }

    /** Forgets every rollup (after history was rewritten, e.g. by a backfill); the next run rebuilds them. */
    public void reset() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("TRUNCATE sdp.metric_rollups_hourly");
            jdbc.update("UPDATE sdp.metric_rollup_state SET rolled_until = NULL, updated_at = now()");
        });
    }

    Optional<Instant> rolledUntil() {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT rolled_until FROM sdp.metric_rollup_state", OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    /** End of the last complete hour: none until every registered partition has persisted something. */
    private Optional<Instant> rollableUntil() {
        OffsetDateTime lowest = jdbc.queryForObject("""
                SELECT CASE WHEN count(*) = 0 OR min(watermark) = '-infinity' THEN NULL
                            ELSE LEAST(min(watermark), now()) END
                  FROM sdp.ingestion_watermarks
                """, OffsetDateTime.class);
        return Optional.ofNullable(lowest)
                .map(w -> w.toInstant().minus(WATERMARK_MARGIN).truncatedTo(ChronoUnit.HOURS));
    }

    /** Rolls up the next day (or less) after {@code rolled_until}; {@code null} when everything rollable is done. */
    private Long rollUpNextBatch(Instant oldest, Instant rollableUntil) {
        OffsetDateTime current = jdbc.queryForObject(
                "SELECT rolled_until FROM sdp.metric_rollup_state FOR UPDATE", OffsetDateTime.class);
        Instant start = current == null || current.toInstant().isBefore(oldest) ? oldest : current.toInstant();
        if (!start.isBefore(rollableUntil)) {
            return null;
        }
        Instant end = start.plus(BATCH).isBefore(rollableUntil) ? start.plus(BATCH) : rollableUntil;
        jdbc.update("DELETE FROM sdp.metric_rollups_hourly WHERE hour >= ? AND hour < ?", Timestamp.from(start), Timestamp.from(end));
        long rows = jdbc.update("""
                INSERT INTO sdp.metric_rollups_hourly (metric, hour, device_id, site_id, min_value, max_value, sum_value,
                                                       sample_count, first_at, first_value, last_at, last_value)
                SELECT e.key, date_trunc('hour', m.measured_at, 'UTC'), m.device_id, max(m.site_id),
                       min(e.v), max(e.v), sum(e.v), count(*),
                       min(m.measured_at), (array_agg(e.v ORDER BY m.measured_at))[1],
                       max(m.measured_at), (array_agg(e.v ORDER BY m.measured_at DESC))[1]
                  FROM sdp.measurements m
                 CROSS JOIN LATERAL (SELECT key, value::text::float8 AS v
                                       FROM jsonb_each(m.metrics) WHERE jsonb_typeof(value) = 'number') e
                 WHERE m.measured_at >= ? AND m.measured_at < ?
                 GROUP BY e.key, date_trunc('hour', m.measured_at, 'UTC'), m.device_id
                """, Timestamp.from(start), Timestamp.from(end));
        jdbc.update("UPDATE sdp.metric_rollup_state SET rolled_until = ?, updated_at = now()", Timestamp.from(end));
        return rows;
    }
}
