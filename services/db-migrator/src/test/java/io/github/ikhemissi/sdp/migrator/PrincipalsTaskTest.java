package io.github.ikhemissi.sdp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrincipalsTaskTest {

    @Test
    void adminUrlTargetsThePostgresDatabaseAndKeepsParameters() {
        assertThat(PrincipalsTask.adminDatabaseUrl(
                "jdbc:postgresql://psql.example.com:5432/sdp?sslmode=require&authenticationPluginClassName=x", "postgres"))
                .isEqualTo("jdbc:postgresql://psql.example.com:5432/postgres?sslmode=require&authenticationPluginClassName=x");
        assertThat(PrincipalsTask.adminDatabaseUrl("jdbc:postgresql://localhost:55432/sdp", "postgres"))
                .isEqualTo("jdbc:postgresql://localhost:55432/postgres");
    }
}
