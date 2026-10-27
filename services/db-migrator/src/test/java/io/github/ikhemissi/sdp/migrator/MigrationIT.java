package io.github.ikhemissi.sdp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.codecs.Metric;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 3.1 — V1 schema, partitions, retention and maintenance on PostgreSQL 18 + pg_partman. */
@SpringBootTest
class MigrationIT extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MaintenanceTask maintenance;

    @Autowired
    Flyway flyway;

    @Test
    void schemaIsMigratedAndMigrationIsRepeatable() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sdp.flyway_schema_history WHERE version = '1' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'sdp'", String.class))
                .contains("sites", "devices", "metric_definitions", "measurements", "rejected_messages", "ingested_messages");

        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void metricDefinitionsAreSeededFromTheCodecCatalogue() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sdp.metric_definitions", Integer.class))
                .isEqualTo(Metric.values().length);
        assertThat(jdbc.queryForObject("SELECT unit FROM sdp.metric_definitions WHERE name = 'pressure'", String.class))
                .isEqualTo("hPa");
    }

    @Test
    void dailyPartitionsArePreCreatedForTheComingWeek() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partman.show_partitions('sdp.measurements')", Integer.class))
                .isGreaterThanOrEqualTo(8);

        insertMeasurement("partition-probe", "now() + interval '6 days'");

        assertThat(jdbc.queryForObject("""
                SELECT tableoid::regclass::text FROM sdp.measurements WHERE device_id = 'partition-probe'
                """, String.class)).startsWith("sdp.measurements_p").isNotEqualTo("sdp.measurements_default");
    }

    @Test
    void maintenanceDropsPartitionsOlderThanRetention() {
        jdbc.queryForList("SELECT partman.create_partition_time('sdp.measurements', ARRAY[now() - interval '40 days'])");
        insertMeasurement("retention-old", "now() - interval '40 days'");
        insertMeasurement("retention-recent", "now()");
        String oldPartition = jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM sdp.measurements WHERE device_id = 'retention-old'", String.class);

        maintenance.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sdp.measurements WHERE device_id = 'retention-old'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sdp.measurements WHERE device_id = 'retention-recent'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, oldPartition)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT retention FROM partman.part_config WHERE parent_table = 'sdp.measurements'", String.class))
                .isEqualTo(30 * 24 * 3600 + " seconds");
    }

    @Test
    void maintenancePurgesExpiredDeduplicationKeysOnly() {
        jdbc.update("INSERT INTO sdp.ingested_messages (device_id, message_key, ingested_at) VALUES ('ledger', 'old', now() - interval '8 days')");
        jdbc.update("INSERT INTO sdp.ingested_messages (device_id, message_key, ingested_at) VALUES ('ledger', 'recent', now() - interval '1 day')");

        maintenance.run();
        maintenance.run();

        assertThat(jdbc.queryForList("SELECT message_key FROM sdp.ingested_messages WHERE device_id = 'ledger'", String.class))
                .containsExactly("recent");
    }

    private void insertMeasurement(String deviceId, String measuredAtExpression) {
        jdbc.update("""
                INSERT INTO sdp.measurements (device_id, site_id, measured_at, received_at, message_key, payload_format, raw_payload, metrics)
                VALUES (?, 'test-site', %s, now(), 'k1', 'ruuvi-df5', '\\x05'::bytea, '{"temperature": 21.5}')
                """.formatted(measuredAtExpression), deviceId);
    }
}
