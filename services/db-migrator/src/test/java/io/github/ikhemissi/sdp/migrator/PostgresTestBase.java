package io.github.ikhemissi.sdp.migrator;

import io.github.ikhemissi.sdp.testsupport.SdpPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Points the migrator at the shared PostgreSQL test container with password authentication. */
abstract class PostgresTestBase {

    static final String DECODER_PRINCIPAL = "id-decoder-test";
    static final PostgreSQLContainer POSTGRES = SdpPostgres.shared();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.azure.passwordless-enabled", () -> "false");
        registry.add("spring.flyway.placeholders.[decoder_principal_name]", () -> DECODER_PRINCIPAL);
    }
}
