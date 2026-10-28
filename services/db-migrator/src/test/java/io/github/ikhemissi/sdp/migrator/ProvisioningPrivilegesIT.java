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

/** M4 task 1.1 — the provisioning API's database role manages site trust records and nothing else. */
@SpringBootTest(properties = "sdp.migrator.tasks=migrate")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProvisioningPrivilegesIT extends PostgresTestBase {

    private static final String PRINCIPAL = "id-provisioning-it";

    @Autowired
    JdbcTemplate admin;

    private JdbcTemplate provisioning;

    @BeforeAll
    void principal() {
        admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + PRINCIPAL + "') THEN "
                + "CREATE ROLE \"" + PRINCIPAL + "\" LOGIN PASSWORD 'provisioning-it-only'; END IF; END $$");
        admin.execute("GRANT sdp_provisioning TO \"" + PRINCIPAL + "\"");
        admin.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('prov-01', 'Provisioning') ON CONFLICT DO NOTHING");
        admin.update("""
                INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                VALUES ('prov-01-dev', 'prov-01', 'ruuvi-df5', now(), now()) ON CONFLICT DO NOTHING""");
        provisioning = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), PRINCIPAL, "provisioning-it-only"));
    }

    @Test
    void managesSitesCertificatesAuditAndOperations() {
        provisioning.update("INSERT INTO sdp.sites (site_id, display_name) VALUES ('prov-02', 'New site')");
        provisioning.update("UPDATE sdp.sites SET display_name = 'Renamed' WHERE site_id = 'prov-02'");
        provisioning.update("""
                INSERT INTO sdp.site_certificates (site_id, fingerprint, slot, subject, issuer, not_before, not_after,
                                                   dps_certificate_name, added_by)
                VALUES ('prov-02', repeat('a', 64), 'primary', 'CN=ca', 'CN=ca', now(), now() + interval '1 year', 'site-prov-02-aaaa', 'oid')""");
        provisioning.update("UPDATE sdp.site_certificates SET slot = 'secondary' WHERE site_id = 'prov-02'");
        provisioning.update("INSERT INTO sdp.site_audit_events (site_id, actor_id, action) VALUES ('prov-02', 'oid', 'SITE_CREATED')");
        provisioning.update("""
                INSERT INTO sdp.site_operations (id, site_id, kind, status) VALUES (gen_random_uuid(), 'prov-02', 'DISABLE_DEVICES', 'PENDING')""");
        provisioning.update("UPDATE sdp.site_operations SET status = 'DONE' WHERE site_id = 'prov-02'");
        provisioning.update("UPDATE sdp.devices SET disabled_by_platform = true WHERE site_id = 'prov-01'");
        provisioning.update("DELETE FROM sdp.site_certificates WHERE site_id = 'prov-02'");

        assertThat(provisioning.queryForObject("SELECT count(*) FROM sdp.site_audit_events WHERE site_id = 'prov-02'", Long.class)).isOne();
    }

    @Test
    void cannotRewriteHistoryOrTouchTelemetry() {
        assertDenied("UPDATE sdp.site_audit_events SET action = 'X'", "site_audit_events");
        assertDenied("DELETE FROM sdp.site_audit_events", "site_audit_events");
        assertDenied("DELETE FROM sdp.sites WHERE site_id = 'prov-01'", "sites");
        assertDenied("UPDATE sdp.devices SET display_name = 'x' WHERE device_id = 'prov-01-dev'", "devices");
        assertDenied("SELECT count(*) FROM sdp.measurements", "measurements");
        assertDenied("SELECT display_name FROM sdp.devices", "devices");
    }

    private void assertDenied(String sql, String table) {
        assertThatThrownBy(() -> provisioning.queryForList(sql))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseMessage("ERROR: permission denied for table " + table);
    }
}
