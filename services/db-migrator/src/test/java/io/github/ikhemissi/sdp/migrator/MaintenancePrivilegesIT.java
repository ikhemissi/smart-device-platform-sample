package io.github.ikhemissi.sdp.migrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** M9 task 1.2 — maintenance: the job writes assessments and the queue, the device API reads them and records statuses. */
@SpringBootTest(properties = "sdp.migrator.tasks=migrate")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaintenancePrivilegesIT extends PostgresTestBase {

    @Autowired
    JdbcTemplate admin;

    @Autowired
    MaintenanceTask maintenance;

    private JdbcTemplate catalog;
    private JdbcTemplate insights;

    @BeforeAll
    void principals() {
        catalog = principal("id-catalog-maintenance-it", "sdp_catalog");
        insights = principal("id-insights-maintenance-it", "sdp_insights");
        admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('mt-paris-01', 'Paris') ON CONFLICT DO NOTHING");
        for (String device : new String[] {"mt-paris-01-ruuvi5-001", "mt-paris-01-ruuvi5-002", "mt-paris-01-ruuvi5-003",
                "mt-paris-01-ruuvi5-004", "mt-paris-01-ruuvi5-005"}) {
            admin.update("""
                    INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                    VALUES (?, 'mt-paris-01', 'ruuvi-df5', now() - interval '20 days', now()) ON CONFLICT DO NOTHING""", device);
        }
    }

    private JdbcTemplate principal(String name, String groupRole) {
        admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + name + "') THEN "
                + "CREATE ROLE \"" + name + "\" LOGIN PASSWORD 'maintenance-it-only'; END IF; END $$");
        admin.execute("GRANT " + groupRole + " TO \"" + name + "\"");
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), name, "maintenance-it-only"));
    }

    private long item(JdbcTemplate as, String device) {
        return as.queryForObject("""
                INSERT INTO sdp.maintenance_items (device_id, site_id, probability, reasons, battery)
                VALUES (?, 'mt-paris-01', 0.9, '[{"rule": "days_left", "value": 3}]', '{"level": 2.62}') RETURNING id""",
                Long.class, device);
    }

    @Test
    void jobReadsItsSourcesAndWritesAssessmentsAndItems() {
        insights.queryForList("SELECT device_id, measured_at FROM sdp.measurements LIMIT 1");
        insights.queryForList("SELECT hour, device_id FROM sdp.metric_rollups_hourly LIMIT 1");
        insights.queryForList("SELECT device_id, received_at FROM sdp.rejected_messages LIMIT 1");
        insights.queryForList("SELECT device_id, site_id, last_payload_format, last_seen_at FROM sdp.devices LIMIT 1");

        insights.update("""
                INSERT INTO sdp.device_maintenance (device_id, site_id, assessed_at, battery, signals, probability, decision_model,
                                                    decision_model_version)
                VALUES ('mt-paris-01-ruuvi5-001', 'mt-paris-01', now(), null, '{"minutes_since_last": 1}', 0.1, 'decision-1', '1')
                ON CONFLICT (device_id) DO UPDATE SET probability = excluded.probability, assessed_at = excluded.assessed_at""");
        long id = item(insights, "mt-paris-01-ruuvi5-001");
        insights.update("UPDATE sdp.maintenance_items SET work_order = 'Replace the battery.', work_order_model = 'gpt-6-luna', "
                + "work_order_at = now(), probability = 0.95, updated_at = now() WHERE id = ?", id);
        insights.update("UPDATE sdp.maintenance_items SET status = 'resolved', resolved_at = now() WHERE id = ?", id);

        assertDenied(insights, "DELETE FROM sdp.maintenance_items WHERE id = " + id, "maintenance_items");
        assertDenied(insights, "DELETE FROM sdp.device_maintenance", "device_maintenance");
        assertDenied(insights, "SELECT raw_payload FROM sdp.rejected_messages", "rejected_messages");
    }

    @Test
    void deviceApiReadsAndRecordsStatusesOnly() {
        long id = item(admin, "mt-paris-01-ruuvi5-002");
        assertThat(catalog.queryForObject("SELECT reasons::text FROM sdp.maintenance_items WHERE id = ?", String.class, id))
                .contains("days_left");
        catalog.queryForList("SELECT device_id, battery, signals, probability FROM sdp.device_maintenance LIMIT 1");
        catalog.update("UPDATE sdp.maintenance_items SET status = 'done', status_by = 'Erin', status_at = now(), "
                + "status_comment = 'battery replaced', updated_at = now() WHERE id = ?", id);

        assertDenied(catalog, "UPDATE sdp.maintenance_items SET probability = 0.1 WHERE id = " + id, "maintenance_items");
        assertDenied(catalog, "INSERT INTO sdp.maintenance_items (device_id, site_id, probability, reasons) "
                + "VALUES ('mt-paris-01-ruuvi5-002', 'mt-paris-01', 0.9, '[]')", "maintenance_items");
        assertDenied(catalog, "DELETE FROM sdp.maintenance_items", "maintenance_items");
        assertDenied(catalog, "UPDATE sdp.device_maintenance SET probability = 0.1", "device_maintenance");
    }

    @Test
    void oneOpenOrPlannedItemPerDevice() {
        long first = item(insights, "mt-paris-01-ruuvi5-003");
        assertThatThrownBy(() -> item(insights, "mt-paris-01-ruuvi5-003")).as("second open item")
                .isInstanceOf(DuplicateKeyException.class);
        catalog.update("UPDATE sdp.maintenance_items SET status = 'planned' WHERE id = ?", first);
        assertThatThrownBy(() -> item(insights, "mt-paris-01-ruuvi5-003")).as("open while planned")
                .isInstanceOf(DuplicateKeyException.class);
        catalog.update("UPDATE sdp.maintenance_items SET status = 'done', status_at = now() WHERE id = ?", first);
        assertThat(item(insights, "mt-paris-01-ruuvi5-003")).as("a closed item frees the device").isGreaterThan(first);
    }

    @Test
    void commentIsAtMostOneThousandCharacters() {
        long id = item(admin, "mt-paris-01-ruuvi5-004");
        assertThatThrownBy(() -> catalog.update("UPDATE sdp.maintenance_items SET status_comment = ? WHERE id = ?", "x".repeat(1001), id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void maintenanceDeletesOldClosedItemsOnly() {
        String device = "mt-paris-01-ruuvi5-005";
        long oldDone = admin.queryForObject("""
                INSERT INTO sdp.maintenance_items (device_id, site_id, status, probability, reasons, opened_at, updated_at, status_at)
                VALUES (?, 'mt-paris-01', 'done', 0.9, '[]', now() - interval '45 days', now() - interval '40 days',
                        now() - interval '40 days') RETURNING id""", Long.class, device);
        long oldResolved = admin.queryForObject("""
                INSERT INTO sdp.maintenance_items (device_id, site_id, status, probability, reasons, opened_at, updated_at, resolved_at)
                VALUES (?, 'mt-paris-01', 'resolved', 0.6, '[]', now() - interval '45 days', now() - interval '40 days',
                        now() - interval '40 days') RETURNING id""", Long.class, device);
        long recentDone = admin.queryForObject("""
                INSERT INTO sdp.maintenance_items (device_id, site_id, status, probability, reasons, status_at)
                VALUES (?, 'mt-paris-01', 'done', 0.9, '[]', now() - interval '2 days') RETURNING id""", Long.class, device);
        long oldOpen = admin.queryForObject("""
                INSERT INTO sdp.maintenance_items (device_id, site_id, probability, reasons, opened_at, updated_at)
                VALUES (?, 'mt-paris-01', 0.9, '[]', now() - interval '45 days', now() - interval '40 days') RETURNING id""",
                Long.class, device);

        maintenance.run();

        assertThat(exists(oldDone)).isFalse();
        assertThat(exists(oldResolved)).isFalse();
        assertThat(exists(recentDone)).isTrue();
        assertThat(exists(oldOpen)).as("open items stay in the queue").isTrue();
    }

    private boolean exists(long id) {
        return admin.queryForObject("SELECT count(*) FROM sdp.maintenance_items WHERE id = ?", Integer.class, id) == 1;
    }

    private static void assertDenied(JdbcTemplate as, String sql, String table) {
        assertThatThrownBy(() -> as.execute(sql)).as(sql).isInstanceOf(DataAccessException.class)
                .hasRootCauseMessage("ERROR: permission denied for table " + table);
    }
}
