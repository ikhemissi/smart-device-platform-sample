package io.github.ikhemissi.sdp.migrator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * Creates the database principals of the workload identities and grants them their group role (ADR-0005).
 *
 * <p>On Azure Database for PostgreSQL, Entra principals can only be created through the {@code pgaadauth}
 * functions, which exist in the {@code postgres} database only; principals are created there (roles are
 * cluster-wide). A same-named plain role (e.g. left by an earlier run) is replaced by the Entra principal. On plain
 * PostgreSQL (local, tests) principals are {@code LOGIN} roles.
 */
@Component
public class PrincipalsTask {

    private static final Logger log = LoggerFactory.getLogger(PrincipalsTask.class);

    private final DataSourceProperties dataSource;
    // Plain DataSource (not JdbcTemplate): JdbcTemplate waits for the Flyway migration this task is part of.
    private final DataSource database;
    private final MigratorProperties properties;

    public PrincipalsTask(DataSourceProperties dataSource, DataSource database, MigratorProperties properties) {
        this.dataSource = dataSource;
        this.database = database;
        this.properties = properties;
    }

    /** Before migrations: makes sure every configured principal exists. */
    public void ensurePrincipals() {
        if (properties.principals().isEmpty()) {
            return;
        }
        String url = adminDatabaseUrl(dataSource.determineUrl(), properties.adminDatabase());
        try (Connection admin = DriverManager.getConnection(url, dataSource.determineUsername(), dataSource.determinePassword())) {
            boolean entra = exists(admin, "SELECT 1 FROM pg_proc WHERE proname = 'pgaadauth_create_principal_with_oid'");
            for (MigratorProperties.Principal principal : properties.principals()) {
                if (entra) {
                    ensureEntraPrincipal(admin, principal);
                } else if (!roleExists(admin, principal.name())) {
                    execute(admin, "CREATE ROLE " + quote(principal.name()) + " LOGIN");
                    log.info("Created database role {}", principal.name());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create workload database principals", e);
        }
    }

    private void ensureEntraPrincipal(Connection admin, MigratorProperties.Principal principal) throws SQLException {
        if (principal.objectId() == null || principal.objectId().isBlank()) {
            throw new IllegalStateException("object id required to create Entra principal " + principal.name());
        }
        if (entraPrincipalExists(admin, principal.name())) {
            return;
        }
        if (roleExists(admin, principal.name())) {
            log.warn("Replacing plain database role {} by its Entra principal", principal.name());
            execute(admin, "DROP ROLE " + quote(principal.name()));
        }
        try (PreparedStatement create = admin.prepareStatement(
                "SELECT pgaadauth_create_principal_with_oid(?, ?, 'service', false, false)")) {
            create.setString(1, principal.name());
            create.setString(2, principal.objectId());
            create.execute();
        }
        log.info("Created Entra principal {} (object id {})", principal.name(), principal.objectId());
    }

    private static boolean entraPrincipalExists(Connection admin, String name) throws SQLException {
        try (PreparedStatement query = admin.prepareStatement(
                "SELECT 1 FROM pgaadauth_list_principals(false) WHERE rolname = ? UNION SELECT 1 FROM pgaadauth_list_principals(true) WHERE rolname = ?")) {
            query.setString(1, name);
            query.setString(2, name);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** After migrations (group roles exist): grants each principal its group role. Idempotent. */
    public void grantGroupRoles() {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            for (MigratorProperties.Principal principal : properties.principals()) {
                statement.execute("GRANT " + quote(principal.groupRole()) + " TO " + quote(principal.name()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot grant group roles to workload principals", e);
        }
    }

    static String adminDatabaseUrl(String url, String database) {
        return url.replaceFirst("^(jdbc:postgresql://[^/]+/)[^?]*", "$1" + database);
    }

    private static boolean roleExists(Connection connection, String role) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            query.setString(1, role);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean exists(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
