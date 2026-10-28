package io.github.ikhemissi.sdp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ikhemissi.sdp.testsupport.SdpPostgres;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** M3 task 1.1 — V4 creates device_metrics and ingestion_watermarks, backfilled from measurements stored before it. */
class TelemetryHistoryMigrationIT {

    private static final String DATABASE = "sdp_v4_backfill";
    private static JdbcTemplate jdbc;
    private static Flyway flyway;

    @BeforeAll
    static void migrateToV3AndSeed() {
        PostgreSQLContainer postgres = SdpPostgres.shared();
        JdbcTemplate server = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        server.execute("DROP DATABASE IF EXISTS " + DATABASE);
        server.execute("CREATE DATABASE " + DATABASE);
        String url = postgres.getJdbcUrl().replaceFirst("/" + SdpPostgres.DATABASE + "(\\?|$)", "/" + DATABASE + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        flyway = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .schemas("sdp").defaultSchema("sdp").target("3").load();
        flyway.migrate();

        jdbc.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('lyon-01', 'Lyon')");
        for (String device : List.of("lyon-01-ruuvi5-001", "lyon-01-cayenne-001", "lyon-01-silent-001")) {
            jdbc.update("""
                    INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                    VALUES (?, 'lyon-01', 'ruuvi-df5', now() - interval '2 days', now())""", device);
        }
        insert("lyon-01-ruuvi5-001", "now() - interval '2 days'", "{\"temperature\": 20.1, \"humidity\": 40, \"tx_power\": 4}");
        insert("lyon-01-ruuvi5-001", "now() - interval '1 hour'", "{\"temperature\": 21.5, \"humidity\": 41}");
        insert("lyon-01-cayenne-001", "now() - interval '3 hours'", "{\"temperature_ch1\": 19, \"temperature_ch2\": 4.5, \"illuminance\": 120}");

        flyway = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .schemas("sdp").defaultSchema("sdp").load();
        flyway.migrate();
    }

    private static void insert(String device, String measuredAt, String metrics) {
        jdbc.update("""
                INSERT INTO sdp.measurements (device_id, site_id, measured_at, received_at, message_key, payload_format, raw_payload, metrics)
                VALUES (?, 'lyon-01', %s, now(), md5(random()::text), 'ruuvi-df5', '\\x05'::bytea, ?::jsonb)""".formatted(measuredAt),
                device, metrics);
    }

    @Test
    void backfillListsEveryMetricKeyWithItsLastMeasurementTime() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT device_id, metric,
                       round(extract(epoch FROM now() - last_seen_at) / 3600) AS hours_ago
                  FROM sdp.device_metrics ORDER BY device_id, metric""");

        assertThat(rows).extracting(r -> r.get("device_id") + "/" + r.get("metric") + "@" + r.get("hours_ago")).containsExactly(
                "lyon-01-cayenne-001/illuminance@3",
                "lyon-01-cayenne-001/temperature_ch1@3",
                "lyon-01-cayenne-001/temperature_ch2@3",
                "lyon-01-ruuvi5-001/humidity@1",
                "lyon-01-ruuvi5-001/temperature@1",
                "lyon-01-ruuvi5-001/tx_power@48");
    }

    @Test
    void watermarksStartEmpty() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sdp.ingestion_watermarks", Integer.class)).isZero();
    }

    @Test
    void historyRolesHaveTheirPrivileges() {
        assertThat(privileges("sdp_ingest", "device_metrics")).containsExactlyInAnyOrder("INSERT", "SELECT", "UPDATE");
        assertThat(privileges("sdp_ingest", "ingestion_watermarks")).containsExactlyInAnyOrder("INSERT", "SELECT", "UPDATE");
        assertThat(privileges("sdp_catalog", "device_metrics")).containsExactly("SELECT");
        assertThat(privileges("sdp_catalog", "ingestion_watermarks")).containsExactly("SELECT");
    }

    private List<String> privileges(String role, String table) {
        return jdbc.queryForList("""
                SELECT privilege_type FROM information_schema.role_table_grants
                 WHERE grantee = ? AND table_schema = 'sdp' AND table_name = ?""", String.class, role, table);
    }
}
