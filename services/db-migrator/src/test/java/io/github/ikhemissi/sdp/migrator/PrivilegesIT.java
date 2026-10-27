package io.github.ikhemissi.sdp.migrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 3.2 — workload principals are created by the migrator with least-privilege grants
 * (spec platform-security: "Least privilege per workload" / "Decoder cannot change schema").
 */
@SpringBootTest
class PrivilegesIT extends PostgresTestBase {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @Autowired
    PrincipalsTask principals;

    @Test
    void decoderPrincipalIsCreatedAndMemberOfTheIngestionRole() {
        assertThat(jdbc.queryForObject("SELECT pg_has_role(?, 'sdp_ingest', 'MEMBER')", Boolean.class, DECODER_PRINCIPAL)).isTrue();
        assertThat(jdbc.queryForObject("SELECT rolcreaterole OR rolcreatedb OR rolsuper FROM pg_roles WHERE rolname = ?",
                Boolean.class, DECODER_PRINCIPAL)).isFalse();
        assertThatCode(() -> {
            principals.ensurePrincipals();
            flyway.migrate();
            principals.grantGroupRoles();
        }).as("principal creation and grants are idempotent").doesNotThrowAnyException();
    }

    @Test
    void decoderCanPerformIngestionWrites() throws SQLException {
        try (Connection c = asDecoder(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO sdp.sites (site_id, display_name) VALUES ('priv-site', 'priv-site') ON CONFLICT DO NOTHING");
            s.execute("""
                    INSERT INTO sdp.ingested_messages (device_id, message_key) VALUES ('priv-dev', 'm1')
                    ON CONFLICT DO NOTHING RETURNING device_id, message_key
                    """);
            s.execute("""
                    INSERT INTO sdp.measurements (device_id, site_id, measured_at, received_at, message_key, payload_format, raw_payload, metrics)
                    VALUES ('priv-dev', 'priv-site', now(), now(), 'm1', 'ruuvi-df5', '\\x05'::bytea, '{}')
                    """);
            s.execute("""
                    INSERT INTO sdp.rejected_messages (received_at, device_id, message_key, reason, raw_payload)
                    VALUES (now(), 'priv-dev', 'm2', 'UNKNOWN_FORMAT', '\\x7f'::bytea)
                    """);
            for (int i = 0; i < 2; i++) {
                s.execute("""
                        INSERT INTO sdp.devices (device_id, site_id, last_payload_format, first_seen_at, last_seen_at)
                        VALUES ('priv-dev', 'priv-site', 'ruuvi-df5', now(), now())
                        ON CONFLICT (device_id) DO UPDATE
                           SET last_seen_at = GREATEST(sdp.devices.last_seen_at, EXCLUDED.last_seen_at),
                               last_payload_format = EXCLUDED.last_payload_format
                        """);
            }
        }
    }

    @Test
    void decoderCannotChangeTheSchema() {
        assertDenied("CREATE TABLE sdp.intruder (id int)");
        assertDenied("DROP TABLE sdp.measurements");
        assertDenied("ALTER TABLE sdp.devices ADD COLUMN intruder text");
        assertDenied("TRUNCATE sdp.measurements");
    }

    @Test
    void decoderCannotEditDeviceMetadataNorReadOrDeleteTelemetry() {
        assertDenied("UPDATE sdp.devices SET display_name = 'renamed'");
        assertDenied("SELECT count(*) FROM sdp.measurements");
        assertDenied("DELETE FROM sdp.measurements");
        assertDenied("DELETE FROM sdp.ingested_messages");
    }

    private void assertDenied(String sql) {
        assertThatThrownBy(() -> {
            try (Connection c = asDecoder(); Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }).as(sql).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).as(sql).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    /** A dedicated (non-pooled) connection switched to the decoder principal; discarded on close. */
    private static Connection asDecoder() throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement s = c.createStatement()) {
            s.execute("SET ROLE \"" + DECODER_PRINCIPAL + "\"");
        }
        return c;
    }
}
