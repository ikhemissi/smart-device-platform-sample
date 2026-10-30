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

/** M8 task 1.1 — site digests: the digest job writes them, the device API reads them and records requests only. */
@SpringBootTest(properties = "sdp.migrator.tasks=migrate")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DigestPrivilegesIT extends PostgresTestBase {

    @Autowired
    JdbcTemplate admin;

    @Autowired
    MaintenanceTask maintenance;

    private JdbcTemplate catalog;
    private JdbcTemplate insights;

    @BeforeAll
    void principals() {
        catalog = principal("id-catalog-digests-it", "sdp_catalog");
        insights = principal("id-insights-digests-it", "sdp_insights");
        admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('dg-paris-01', 'Paris'), ('dg-lyon-01', 'Lyon') "
                + "ON CONFLICT DO NOTHING");
    }

    private JdbcTemplate principal(String name, String groupRole) {
        admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + name + "') THEN "
                + "CREATE ROLE \"" + name + "\" LOGIN PASSWORD 'digests-it-only'; END IF; END $$");
        admin.execute("GRANT " + groupRole + " TO \"" + name + "\"");
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), name, "digests-it-only"));
    }

    private long digest(JdbcTemplate as, String site, String generatedAt) {
        return as.queryForObject("""
                INSERT INTO sdp.site_digests (site_id, trigger, period_from, period_to, comparison_from, generated_at, figures)
                VALUES (?, 'scheduled', %1$s - interval '1 day', %1$s, %1$s - interval '8 days', %1$s, '{"devices": {}}')
                RETURNING id""".formatted(generatedAt), Long.class, site);
    }

    @Test
    void jobReadsTheFiguresSourcesButNotRecipientsNorPayloads() {
        insights.queryForList("""
                SELECT device_id, metric, status, direction, opened_at, closed_at, worst_value FROM sdp.alert_events LIMIT 1""");
        insights.queryForList("SELECT site_id, reason, received_at FROM sdp.rejected_messages LIMIT 1");
        insights.queryForList("SELECT site_id, slot, subject, not_after FROM sdp.site_certificates LIMIT 1");

        assertDenied(insights, "SELECT recipients FROM sdp.alert_events", "alert_events");
        assertDenied(insights, "SELECT raw_payload FROM sdp.rejected_messages", "rejected_messages");
        assertDenied(insights, "SELECT added_by FROM sdp.site_certificates", "site_certificates");
    }

    @Test
    void jobWritesDigestsAndServesRequests() {
        long request = catalog.queryForObject(
                "INSERT INTO sdp.site_digest_requests (site_id, requested_by) VALUES ('dg-paris-01', 'Erin') RETURNING id", Long.class);
        long id = digest(insights, "dg-paris-01", "now()");
        insights.update("UPDATE sdp.site_digests SET brief = 'All good.', brief_model = 'gpt-6-luna', brief_generated_at = now() "
                + "WHERE id = ?", id);
        insights.update("UPDATE sdp.site_digest_requests SET processed_at = now(), digest_id = ? WHERE id = ?", id, request);

        assertDenied(insights, "DELETE FROM sdp.site_digests WHERE id = " + id, "site_digests");
        assertDenied(insights, "INSERT INTO sdp.site_digest_requests (site_id, requested_by) VALUES ('dg-paris-01', 'x')",
                "site_digest_requests");
        assertDenied(insights, "UPDATE sdp.site_digest_requests SET requested_by = 'x' WHERE id = " + request, "site_digest_requests");
    }

    @Test
    void deviceApiReadsDigestsAndRecordsOneRequestPerSite() {
        long id = digest(admin, "dg-lyon-01", "now()");
        assertThat(catalog.queryForObject("SELECT figures::text FROM sdp.site_digests WHERE id = ?", String.class, id))
                .contains("devices");
        catalog.update("INSERT INTO sdp.site_digest_requests (site_id, requested_by) VALUES ('dg-lyon-01', 'Erin')");
        assertThatThrownBy(() -> catalog.update(
                "INSERT INTO sdp.site_digest_requests (site_id, requested_by) VALUES ('dg-lyon-01', 'Ada')"))
                .as("one pending request per site").isInstanceOf(DuplicateKeyException.class);

        assertDenied(catalog, "INSERT INTO sdp.site_digests (site_id, trigger, period_from, period_to, comparison_from, figures) "
                + "VALUES ('dg-lyon-01', 'requested', now() - interval '1 day', now(), now() - interval '8 days', '{}')", "site_digests");
        assertDenied(catalog, "UPDATE sdp.site_digest_requests SET processed_at = now()", "site_digest_requests");
        assertDenied(catalog, "DELETE FROM sdp.site_digest_requests", "site_digest_requests");
    }

    @Test
    void briefAndItsTimeGoTogether() {
        long id = digest(insights, "dg-paris-01", "now()");
        assertThatThrownBy(() -> insights.update("UPDATE sdp.site_digests SET brief = 'x' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void maintenanceDeletesOldDigestsAndProcessedRequests() {
        long old = digest(admin, "dg-paris-01", "now() - interval '40 days'");
        long recent = digest(admin, "dg-paris-01", "now() - interval '1 day'");
        long processed = admin.queryForObject("""
                INSERT INTO sdp.site_digest_requests (site_id, requested_by, requested_at, processed_at)
                VALUES ('dg-paris-01', 'Erin', now() - interval '41 days', now() - interval '40 days') RETURNING id""", Long.class);

        maintenance.run();

        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.site_digests WHERE id = ?", Integer.class, old)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.site_digests WHERE id = ?", Integer.class, recent)).isOne();
        assertThat(admin.queryForObject("SELECT count(*) FROM sdp.site_digest_requests WHERE id = ?", Integer.class, processed))
                .isZero();
    }

    private static void assertDenied(JdbcTemplate as, String sql, String table) {
        assertThatThrownBy(() -> as.execute(sql)).as(sql).isInstanceOf(DataAccessException.class)
                .hasRootCauseMessage("ERROR: permission denied for table " + table);
    }
}
