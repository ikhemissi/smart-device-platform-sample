package io.github.ikhemissi.sdp.decoder;

import io.github.ikhemissi.sdp.testsupport.SdpPostgres;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared PostgreSQL for decoder integration tests: migrated with the real migrations (libs/db-schema); the decoder
 * connects as the least-privilege decoder principal created by afterMigrate.sql (given a password for tests only).
 */
public final class DecoderDatabase {

    public static final String DECODER_PRINCIPAL = "id-decoder-it";
    private static final String DECODER_PASSWORD = "decoder-it-only";
    private static boolean migrated;

    private DecoderDatabase() {
    }

    public static synchronized PostgreSQLContainer postgres() {
        PostgreSQLContainer postgres = SdpPostgres.shared();
        if (!migrated) {
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("sdp").defaultSchema("sdp")
                    .placeholders(Map.of("decoder_principal_name", DECODER_PRINCIPAL, "decoder_principal_object_id", ""))
                    .load()
                    .migrate();
            admin().execute("ALTER ROLE \"" + DECODER_PRINCIPAL + "\" PASSWORD '" + DECODER_PASSWORD + "'");
            migrated = true;
        }
        return postgres;
    }

    /** Admin access for test setup and assertions (the decoder itself cannot read telemetry). */
    public static JdbcTemplate admin() {
        PostgreSQLContainer postgres = SdpPostgres.shared();
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    public static void registerDecoderDataSource(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> DECODER_PRINCIPAL);
        registry.add("spring.datasource.password", () -> DECODER_PASSWORD);
        registry.add("spring.datasource.azure.passwordless-enabled", () -> "false");
    }
}
