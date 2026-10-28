package io.github.ikhemissi.sdp.migrator;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Database housekeeping, safe to run repeatedly: applies the configured retention to partitioned tables,
 * runs pg_partman maintenance (pre-creates future partitions, drops expired ones), purges old deduplication keys
 * in bounded batches and forgets metrics a device has not reported within the retention period.
 */
@Component
public class MaintenanceTask {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceTask.class);

    private final JdbcTemplate jdbc;
    private final MigratorProperties properties;

    public MaintenanceTask(JdbcTemplate jdbc, MigratorProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public void run() {
        applyRetention();
        jdbc.execute("CALL partman.run_maintenance_proc()");
        long purged = purgeLedger();
        int metrics = jdbc.update("DELETE FROM sdp.device_metrics WHERE last_seen_at < now() - ?::interval",
                interval(properties.retention()));
        log.info("Database maintenance completed: retention={}, ledgerRetention={}, ledgerKeysPurged={}, deviceMetricsPurged={}",
                properties.retention(), properties.ledgerRetention(), purged, metrics);
    }

    private void applyRetention() {
        for (String table : properties.partitionedTables()) {
            int updated = jdbc.update("""
                    UPDATE partman.part_config
                       SET retention = ?, retention_keep_table = false, infinite_time_partitions = true
                     WHERE parent_table = ?
                    """, interval(properties.retention()), table);
            if (updated != 1) {
                throw new IllegalStateException("Table " + table + " is not managed by pg_partman");
            }
        }
    }

    private long purgeLedger() {
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.update("""
                    DELETE FROM sdp.ingested_messages
                     WHERE ctid IN (SELECT ctid FROM sdp.ingested_messages
                                     WHERE ingested_at < now() - ?::interval
                                     LIMIT ?)
                    """, interval(properties.ledgerRetention()), properties.ledgerPurgeBatchSize());
            total += deleted;
        } while (deleted == properties.ledgerPurgeBatchSize());
        return total;
    }

    private static String interval(Duration duration) {
        return duration.toSeconds() + " seconds";
    }
}
