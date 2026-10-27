package io.github.ikhemissi.sdp.migrator;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param tasks                what to run: {@code migrate} (Flyway) and/or {@code maintain} (partitions, retention,
 *                             ledger purge)
 * @param retention            how long measurements and rejected messages are kept (at least 30 days per spec)
 * @param ledgerRetention      how long deduplication keys are kept
 * @param ledgerPurgeBatchSize rows deleted per statement when purging the ledger
 * @param partitionedTables    tables whose partitions are managed by pg_partman
 * @param principals           workload identities to create as database principals, with their group role
 * @param adminDatabase        database where principals are created (Azure: {@code pgaadauth} lives in {@code postgres})
 */
@ConfigurationProperties("sdp.migrator")
public record MigratorProperties(
        @DefaultValue({"migrate", "maintain"}) Set<Task> tasks,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("7d") Duration ledgerRetention,
        @DefaultValue("50000") int ledgerPurgeBatchSize,
        @DefaultValue({"sdp.measurements", "sdp.rejected_messages"}) List<String> partitionedTables,
        @DefaultValue List<Principal> principals,
        @DefaultValue("postgres") String adminDatabase) {

    public enum Task { MIGRATE, MAINTAIN }

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
        if (ledgerPurgeBatchSize <= 0) {
            throw new IllegalArgumentException("sdp.migrator.ledger-purge-batch-size must be positive");
        }
    }
}
