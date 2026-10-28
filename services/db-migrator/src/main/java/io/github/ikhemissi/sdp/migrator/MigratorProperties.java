package io.github.ikhemissi.sdp.migrator;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param tasks                what to run: {@code migrate} (Flyway), {@code maintain} (partitions, retention,
 *                             ledger purge) and, only when explicitly requested, {@code backfill} (demo history)
 * @param retention            how long measurements and rejected messages are kept (at least 30 days per spec)
 * @param ledgerRetention      how long deduplication keys are kept
 * @param ledgerPurgeBatchSize rows deleted per statement when purging the ledger
 * @param partitionedTables    tables whose partitions are managed by pg_partman
 * @param principals           workload identities to create as database principals, with their group role
 * @param adminDatabase        database where principals are created (Azure: {@code pgaadauth} lives in {@code postgres})
 * @param backfill             demo history backfill settings
 */
@ConfigurationProperties("sdp.migrator")
public record MigratorProperties(
        @DefaultValue({"migrate", "maintain"}) Set<Task> tasks,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("7d") Duration ledgerRetention,
        @DefaultValue("50000") int ledgerPurgeBatchSize,
        @DefaultValue({"sdp.measurements", "sdp.rejected_messages"}) List<String> partitionedTables,
        @DefaultValue List<Principal> principals,
        @DefaultValue("postgres") String adminDatabase,
        @DefaultValue Backfill backfill) {

    public enum Task { MIGRATE, MAINTAIN, BACKFILL }

    /**
     * Demo history backfill (M3 design D7): realistic readings for every known device from {@code days} ago up to its
     * first live measurement.
     *
     * @param days     how far back to generate (at most the retention period)
     * @param interval reporting interval of the generated history
     * @param jitter   variation of each reporting instant (±, below half the interval)
     * @param sites    sites to backfill; all when empty
     */
    public record Backfill(
            @DefaultValue("30") int days,
            @DefaultValue("30s") Duration interval,
            @DefaultValue("5s") Duration jitter,
            @DefaultValue List<String> sites) {

        public Backfill {
            sites = sites == null ? List.of() : sites.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (days < 1) {
                throw new IllegalArgumentException("sdp.migrator.backfill.days must be at least 1");
            }
            if (interval.compareTo(Duration.ofSeconds(1)) < 0 || jitter.isNegative() || jitter.multipliedBy(2).compareTo(interval) >= 0) {
                throw new IllegalArgumentException("sdp.migrator.backfill: interval must be >= 1s and jitter in [0, interval / 2)");
            }
        }
    }

    /** A workload identity: principal name, Entra object (principal) id, and the group role carrying its privileges. */
    public record Principal(String name, String objectId, String groupRole) {
    }

    public MigratorProperties {
        tasks = tasks == null || tasks.isEmpty() ? EnumSet.noneOf(Task.class) : EnumSet.copyOf(tasks);
        if (retention.compareTo(Duration.ofDays(30)) < 0) {
            throw new IllegalArgumentException("sdp.migrator.retention must be at least 30 days, got " + retention);
        }
        principals = principals == null ? List.of()
                : principals.stream().filter(p -> p.name() != null && !p.name().isBlank()).toList();
        if (backfill != null && Duration.ofDays(backfill.days()).compareTo(retention) > 0) {
            throw new IllegalArgumentException("sdp.migrator.backfill.days must not exceed the retention (" + retention + ")");
        }
        if (ledgerPurgeBatchSize <= 0) {
            throw new IllegalArgumentException("sdp.migrator.ledger-purge-batch-size must be positive");
        }
    }
}
