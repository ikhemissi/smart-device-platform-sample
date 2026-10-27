package io.github.ikhemissi.sdp.deviceapi;

import io.github.ikhemissi.sdp.testsupport.SdpPostgres;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared PostgreSQL migrated with the real migrations; the device API connects as a least-privilege member of
 * {@code sdp_catalog}, as the migrator sets it up (with a password, for tests only).
 */
public final class CatalogDatabase {

    public static final String PRINCIPAL = "id-device-api-it";
    private static final String PASSWORD = "device-api-it-only";
    private static boolean migrated;

    private CatalogDatabase() {
    }

    public static synchronized PostgreSQLContainer postgres() {
        PostgreSQLContainer postgres = SdpPostgres.shared();
        if (!migrated) {
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("sdp").defaultSchema("sdp")
                    .load()
                    .migrate();
            JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            admin.execute("CREATE ROLE \"" + PRINCIPAL + "\" LOGIN PASSWORD '" + PASSWORD + "'");
            admin.execute("GRANT sdp_catalog TO \"" + PRINCIPAL + "\"");
            migrated = true;
        }
        return postgres;
    }

    /** Admin access (migrated database) for test setup and assertions. */
    public static JdbcTemplate admin() {
        PostgreSQLContainer postgres = postgres();
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    public static JdbcTemplate asCatalog() {
        PostgreSQLContainer postgres = postgres();
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), PRINCIPAL, PASSWORD));
    }

    public static void register(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PRINCIPAL);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.azure.passwordless-enabled", () -> "false");
    }
}
