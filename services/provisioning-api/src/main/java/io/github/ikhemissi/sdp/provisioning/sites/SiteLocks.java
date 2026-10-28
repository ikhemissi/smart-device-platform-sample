package io.github.ikhemissi.sdp.provisioning.sites;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * Session-level PostgreSQL advisory locks on a dedicated connection, for work that spans several transactions (device
 * operations, reconciliation). They share keys with {@link SiteRepository#lockSite}, so trust changes of a site wait
 * for its device operation and vice versa, across replicas.
 */
@Component
public class SiteLocks {

    private final DataSource dataSource;

    public SiteLocks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T withSite(String siteId, Supplier<T> work) {
        return with("sdp.site:" + siteId, true, work);
    }

    /** Runs {@code work} only if no other replica holds the lock; returns {@code null} otherwise. */
    public <T> T tryWith(String name, Supplier<T> work) {
        return with(name, false, work);
    }

    private <T> T with(String key, boolean wait, Supplier<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            if (!lock(connection, wait ? "pg_advisory_lock" : "pg_try_advisory_lock", key) && !wait) {
                return null;
            }
            try {
                return work.get();
            } finally {
                lock(connection, "pg_advisory_unlock", key);
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("Advisory lock " + key + " failed", e);
        }
    }

    private static boolean lock(Connection connection, String function, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + function + "(hashtext(?))")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                Object result = rs.getObject(1);
                return !(result instanceof Boolean b) || b;
            }
        }
    }
}
