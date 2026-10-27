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
            for (MigratorProperties.Principal p : properties.principals()) {
                if (entra) {
                    ensureEntraPrincipal(admin, p);
                } else if (!roleExists(admin, p.name())) {
                    try (Statement s = admin.createStatement()) {
                        s.execute("CREATE ROLE " + quote(p.name()) + " LOGIN");
                    }
                    log.info("Created database role {}", p.name());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create workload database principals", e);
        }
    }

    private void ensureEntraPrincipal(Connection admin, MigratorProperties.Principal p) throws SQLException {
        if (p.objectId() == null || p.objectId().isBlank()) {
            throw new IllegalStateException("object id required to create Entra principal " + p.name());
        }
        try (PreparedStatement q = admin.prepareStatement(
                "SELECT 1 FROM pgaadauth_list_principals(false) WHERE rolname = ? UNION SELECT 1 FROM pgaadauth_list_principals(true) WHERE rolname = ?")) {
            q.setString(1, p.name());
            q.setString(2, p.name());
            try (ResultSet rs = q.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        if (roleExists(admin, p.name())) {
            log.warn("Replacing plain database role {} by its Entra principal", p.name());
            try (Statement s = admin.createStatement()) {
                s.execute("DROP ROLE " + quote(p.name()));
            }
        }
        try (PreparedStatement create = admin.prepareStatement(
                "SELECT pgaadauth_create_principal_with_oid(?, ?, 'service', false, false)")) {
            create.setString(1, p.name());
            create.setString(2, p.objectId());
            create.execute();
        }
        log.info("Created Entra principal {} (object id {})", p.name(), p.objectId());
    }

    /** After migrations (group roles exist): grants each principal its group role. Idempotent. */
    public void grantGroupRoles() {
        try (Connection c = database.getConnection(); Statement s = c.createStatement()) {
            for (MigratorProperties.Principal p : properties.principals()) {
                s.execute("GRANT " + quote(p.groupRole()) + " TO " + quote(p.name()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot grant group roles to workload principals", e);
        }
    }

    static String adminDatabaseUrl(String url, String database) {
        return url.replaceFirst("^(jdbc:postgresql://[^/]+/)[^?]*", "$1" + database);
    }

    private static boolean roleExists(Connection c, String role) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            q.setString(1, role);
            try (ResultSet rs = q.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean exists(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next();
        }
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
