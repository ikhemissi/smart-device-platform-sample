package io.github.ikhemissi.sdp.migrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** M7 task 1.1 — anomaly records: the detector writes them, the device API reads them and records reviews only. */
@SpringBootTest(properties = "sdp.migrator.tasks=migrate")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnomalyPrivilegesIT extends PostgresTestBase {

    @Autowired
    JdbcTemplate admin;

    @Autowired
    MaintenanceTask maintenance;

    private JdbcTemplate catalog;
    private JdbcTemplate insights;

    @BeforeAll
    void principals() {
        catalog = principal("id-catalog-anomalies-it", "sdp_catalog");
        insights = principal("id-insights-it", "sdp_insights");
    }

    private JdbcTemplate principal(String name, String groupRole) {
        admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + name + "') THEN "
                + "CREATE ROLE \"" + name + "\" LOGIN PASSWORD 'anomalies-it-only'; END IF; END $$");
        admin.execute("GRANT " + groupRole + " TO \"" + name + "\"");
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), name, "anomalies-it-only"));
    }

    private long anomaly(JdbcTemplate as, String endedAt) {
        return as.queryForObject("""
                INSERT INTO sdp.anomalies (device_id, site_id, metric, kind, started_at, ended_at, observed_min, observed_max,
                                           observed_mean, observed_last, usual_value, spread, score, decision, state)
                VALUES ('anomaly-dev', 'anomaly-01', 'temperature', 'deviation', %s - interval '10 minutes', %s,
                        30, 35, 33, 35, 21, 0.4, 30, 'pending', '{}')
                RETURNING id""".formatted(endedAt, endedAt), Long.class);
    }

    @Test
    void detectorReadsDataAndRecordsDecisions() {
        insights.queryForList("SELECT * FROM sdp.measurements LIMIT 1");
        insights.queryForList("SELECT * FROM sdp.metric_rollups_hourly LIMIT 1");
        insights.queryForList("SELECT device_id, site_id, display_name FROM sdp.devices LIMIT 1");
        long id = anomaly(insights, "now()");
        insights.update("""
                UPDATE sdp.anomalies SET decision = 'confirmed', classification = 'real_event',
                       probabilities = '{"real_event": 0.91}', ended_at = now(), updated_at = now() WHERE id = ?""", id);

        assertDenied(insights, "INSERT INTO sdp.measurements (device_id, site_id, measured_at, received_at, message_key, "
                + "payload_format, raw_payload, metrics) VALUES ('x', 'x', now(), now(), 'k', 'f', '\\x00'::bytea, '{}')", "measurements");
        assertDenied(insights, "DELETE FROM sdp.anomalies WHERE id = " + id, "anomalies");
        assertDenied(insights, "SELECT * FROM sdp.alert_thresholds", "alert_thresholds");
    }

    @Test
    void deviceApiOnlyRecordsReviews() {
        long id = anomaly(insights, "now()");
        catalog.update("""
                UPDATE sdp.anomalies SET reviewed_at = now(), reviewed_by = 'Erin Editor', review_verdict = 'correct',
                       review_comment = 'door left open' WHERE id = ?""", id);
        assertThat(catalog.queryForObject("SELECT review_verdict FROM sdp.anomalies WHERE id = ?", String.class, id)).isEqualTo("correct");

        assertDenied(catalog, "UPDATE sdp.anomalies SET decision = 'dismissed' WHERE id = " + id, "anomalies");
        assertDenied(catalog, "UPDATE sdp.anomalies SET probabilities = '{}' WHERE id = " + id, "anomalies");
        assertDenied(catalog, "DELETE FROM sdp.anomalies WHERE id = " + id, "anomalies");
        assertDenied(catalog, "INSERT INTO sdp.anomalies (device_id, site_id, metric, kind, started_at, ended_at, observed_min, "
                + "observed_max, observed_mean, observed_last, usual_value, spread, score, decision, state) VALUES "
                + "('x', 'x', 'x', 'flat', now(), now(), 0, 0, 0, 0, 0, 0, 0, 'pending', '{}')", "anomalies");
    }

    @Test
    void decisionsAndClassificationsAreConsistent() {
        long id = anomaly(insights, "now()");
        assertThatThrownBy(() -> insights.update("UPDATE sdp.anomalies SET decision = 'confirmed' WHERE id = ?", id))
                .as("a classified decision needs a classification").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insights.update("UPDATE sdp.anomalies SET review_verdict = 'maybe' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void maintenanceDeletesAnomaliesOlderThanRetention() {
        long old = anomaly(admin, "now() - interval '40 days'");
        long recent = anomaly(admin, "now() - interval '1 day'");

        maintenance.run();

        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.anomalies WHERE id = ?", Integer.class, old)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.anomalies WHERE id = ?", Integer.class, recent)).isOne();
    }

    private static void assertDenied(JdbcTemplate as, String sql, String table) {
        assertThatThrownBy(() -> as.execute(sql)).as(sql).isInstanceOf(DataAccessException.class)
                .hasRootCauseMessage("ERROR: permission denied for table " + table);
    }
}
