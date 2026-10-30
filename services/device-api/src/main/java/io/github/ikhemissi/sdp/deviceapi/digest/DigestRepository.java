package io.github.ikhemissi.sdp.deviceapi.digest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Digests (read) and regeneration requests (read, insert) of the database role {@code sdp_catalog} (migration V9). */
@Repository
public class DigestRepository {

    private final JdbcClient jdbc;

    public DigestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean siteExists(String siteId) {
        return jdbc.sql("SELECT count(*) FROM sdp.sites WHERE site_id = :site").param("site", siteId)
                .query(Integer.class).single() > 0;
    }

    Optional<SiteDigest.Digest> latest(String siteId) {
        return jdbc.sql("""
                        SELECT id, trigger, period_from, period_to, comparison_from, generated_at, brief, brief_model,
                               requested_by, figures::text AS figures
                          FROM sdp.site_digests WHERE site_id = :site ORDER BY generated_at DESC, id DESC LIMIT 1""")
                .param("site", siteId).query(DigestRepository::digest).optional();
    }

    Optional<SiteDigest.PendingRequest> pending(String siteId) {
        return jdbc.sql("""
                        SELECT requested_at, requested_by FROM sdp.site_digest_requests
                         WHERE site_id = :site AND processed_at IS NULL""")
                .param("site", siteId)
                .query((rs, row) -> new SiteDigest.PendingRequest(instant(rs, "requested_at"), rs.getString("requested_by")))
                .optional();
    }

    /** Records a request unless one is pending for the site (at most one, by a unique index). */
    void request(String siteId, String requestedBy) {
        jdbc.sql("""
                        INSERT INTO sdp.site_digest_requests (site_id, requested_by) VALUES (:site, :by)
                        ON CONFLICT (site_id) WHERE processed_at IS NULL DO NOTHING""")
                .param("site", siteId).param("by", requestedBy).update();
    }

    private static SiteDigest.Digest digest(ResultSet rs, int row) throws SQLException {
        return new SiteDigest.Digest(rs.getLong("id"), rs.getString("trigger"), instant(rs, "period_from"),
                instant(rs, "period_to"), instant(rs, "comparison_from"), instant(rs, "generated_at"), rs.getString("brief"),
                rs.getString("brief_model"), rs.getString("requested_by"), rs.getString("figures"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
