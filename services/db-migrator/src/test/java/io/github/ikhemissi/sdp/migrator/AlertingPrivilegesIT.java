package io.github.ikhemissi.sdp.migrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** M5 task 3.1 — least-privilege access to thresholds and alerts for the device API and the alert service. */
@SpringBootTest(properties = "sdp.migrator.tasks=migrate")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertingPrivilegesIT extends PostgresTestBase {

    @Autowired
    JdbcTemplate admin;

    private JdbcTemplate catalog;
    private JdbcTemplate alerting;

    @BeforeAll
    void principals() {
        catalog = principal("id-catalog-alerts-it", "sdp_catalog");
        alerting = principal("id-alerting-it", "sdp_alerting");
        admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('alert-01', 'Alerts') ON CONFLICT DO NOTHING");
        admin.update("""
                INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                VALUES ('alert-01-dev', 'alert-01', 'ruuvi-df5', now(), now()) ON CONFLICT DO NOTHING""");
    }

    private JdbcTemplate principal(String name, String groupRole) {
        admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + name + "') THEN "
                + "CREATE ROLE \"" + name + "\" LOGIN PASSWORD 'alerting-it-only'; END IF; END $$");
        admin.execute("GRANT " + groupRole + " TO \"" + name + "\"");
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), name, "alerting-it-only"));
    }

    private UUID threshold() {
        UUID id = UUID.randomUUID();
        catalog.update("""
                INSERT INTO sdp.alert_thresholds (id, device_id, metric, min_value, max_value, recipients, created_by, updated_by)
                VALUES (?, 'alert-01-dev', 'temperature', 2, 8, ARRAY['ops@example.com'], 'oid', 'oid')""", id);
        catalog.update("INSERT INTO sdp.alert_threshold_state (threshold_id) VALUES (?)", id);
        return id;
    }

    private long openAlert(UUID threshold) {
        return alerting.queryForObject("""
                INSERT INTO sdp.alert_events (threshold_id, device_id, site_id, metric, threshold_min, threshold_max, recipients,
                                              status, direction, opened_at, first_value, worst_value, last_value, last_breach_at)
                VALUES (?, 'alert-01-dev', 'alert-01', 'temperature', 2, 8, ARRAY['ops@example.com'], 'OPEN', 'ABOVE', now(),
                        12, 12, 12, now())
                RETURNING id""", Long.class, threshold);
    }

    @Test
    void deviceApiManagesThresholdsAndCancelsTheirAlerts() {
        UUID id = threshold();
        catalog.update("UPDATE sdp.alert_thresholds SET enabled = false, version = version + 1 WHERE id = ?", id);
        catalog.queryForList("SELECT * FROM sdp.alert_threshold_state WHERE threshold_id = ? FOR UPDATE", id);
        long alert = openAlert(id);
        catalog.update("UPDATE sdp.alert_events SET status = 'CANCELLED', closed_at = now() WHERE id = ?", alert);
        catalog.update("DELETE FROM sdp.alert_thresholds WHERE id = ?", id);

        assertThat(admin.queryForObject("SELECT threshold_id IS NULL FROM sdp.alert_events WHERE id = ?", Boolean.class, alert))
                .as("alert history is kept when its threshold is deleted").isTrue();
        assertThat(catalog.queryForList("SELECT * FROM sdp.alert_notifications")).isNotNull();
    }

    @Test
    void deviceApiCannotRewriteAlerts() {
        long alert = openAlert(threshold());
        assertDenied(catalog, "UPDATE sdp.alert_events SET worst_value = 0 WHERE id = " + alert, "alert_events");
        assertDenied(catalog, "DELETE FROM sdp.alert_events", "alert_events");
        assertDenied(catalog, "INSERT INTO sdp.alert_notifications (alert_id, kind, status, recipients) VALUES ("
                + alert + ", 'ALERT', 'PENDING', ARRAY['x@example.com'])", "alert_notifications");
    }

    @Test
    void alertServiceManagesAlertsAndNotifications() {
        UUID id = threshold();
        alerting.queryForList("SELECT * FROM sdp.alert_threshold_state WHERE threshold_id = ? FOR UPDATE", id);
        alerting.update("UPDATE sdp.alert_threshold_state SET last_evaluated_at = now(), last_message_key = 'k' WHERE threshold_id = ?", id);
        long alert = openAlert(id);
        alerting.update("INSERT INTO sdp.alert_notifications (alert_id, kind, status, recipients) VALUES (?, 'ALERT', 'PENDING', ARRAY['ops@example.com'])", alert);
        alerting.update("UPDATE sdp.alert_notifications SET status = 'SENT', sent_at = now() WHERE alert_id = ?", alert);
        alerting.update("UPDATE sdp.alert_events SET status = 'CLOSED', closed_at = now() WHERE id = ?", alert);
        assertThat(alerting.queryForObject("""
                SELECT d.display_name IS NULL AND s.display_name = 'Alerts' FROM sdp.devices d JOIN sdp.sites s USING (site_id)
                WHERE d.device_id = 'alert-01-dev'""", Boolean.class)).isTrue();
        alerting.queryForList("SELECT unit FROM sdp.metric_definitions");
    }

    @Test
    void atMostOneOpenAlertPerThreshold() {
        UUID id = threshold();
        openAlert(id);
        assertThatThrownBy(() -> openAlert(id)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void alertServiceCannotChangeThresholdsOrReadTelemetry() {
        UUID id = threshold();
        assertDenied(alerting, "UPDATE sdp.alert_thresholds SET enabled = false", "alert_thresholds");
        assertDenied(alerting, "DELETE FROM sdp.alert_threshold_state WHERE threshold_id = '" + id + "'", "alert_threshold_state");
        assertDenied(alerting, "DELETE FROM sdp.alert_events", "alert_events");
        assertDenied(alerting, "SELECT count(*) FROM sdp.measurements", "measurements");
        assertDenied(alerting, "SELECT description FROM sdp.devices", "devices");
        assertDenied(alerting, "UPDATE sdp.devices SET display_name = 'x'", "devices");
    }

    private static void assertDenied(JdbcTemplate principal, String sql, String table) {
        assertThatThrownBy(() -> principal.queryForList(sql))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseMessage("ERROR: permission denied for table " + table);
    }
}
