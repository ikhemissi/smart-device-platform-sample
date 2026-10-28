package io.github.ikhemissi.sdp.provisioning.sites;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SiteRepository {

    static final Duration EXPIRING_WITHIN = Duration.ofDays(30);
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public SiteRepository(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public boolean siteExists(String siteId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sdp.sites WHERE site_id = :id)").param("id", siteId)
                .query(Boolean.class).single();
    }

    /** {@code false} if a site with that id already exists. */
    public boolean insertSite(String siteId, String displayName) {
        return jdbc.sql("INSERT INTO sdp.sites (site_id, display_name) VALUES (:id, :name) ON CONFLICT DO NOTHING")
                .param("id", siteId).param("name", displayName).update() == 1;
    }

    /** Serialises trust changes of one site across requests, reconciliation and replicas (transaction-scoped). */
    public void lockSite(String siteId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('sdp.site:' || :id))").param("id", siteId).query().singleRow();
    }

    public List<SiteCertificate> certificates(String siteId) {
        return jdbc.sql("SELECT * FROM sdp.site_certificates WHERE site_id = :id ORDER BY slot")
                .param("id", siteId).query(this::certificate).list();
    }

    public List<SiteCertificate> allCertificates() {
        return jdbc.sql("SELECT * FROM sdp.site_certificates ORDER BY site_id, slot").query(this::certificate).list();
    }

    public void insertCertificate(SiteCertificate c) {
        jdbc.sql("""
                        INSERT INTO sdp.site_certificates (site_id, fingerprint, slot, subject, issuer, not_before, not_after,
                                                           dps_certificate_name, added_by, added_by_name, added_at)
                        VALUES (:site, :fp, :slot, :subject, :issuer, :notBefore, :notAfter, :dps, :by, :byName, :at)""")
                .param("site", c.siteId()).param("fp", c.fingerprint()).param("slot", c.slot())
                .param("subject", c.subject()).param("issuer", c.issuer())
                .param("notBefore", utc(c.notBefore())).param("notAfter", utc(c.notAfter()))
                .param("dps", c.dpsCertificateName()).param("by", c.addedBy()).param("byName", c.addedByName())
                .param("at", utc(c.addedAt()))
                .update();
    }

    public void deleteCertificate(String siteId, String fingerprint) {
        jdbc.sql("DELETE FROM sdp.site_certificates WHERE site_id = :site AND fingerprint = :fp")
                .param("site", siteId).param("fp", fingerprint).update();
    }

    public void setSlot(String siteId, String fingerprint, String slot) {
        jdbc.sql("UPDATE sdp.site_certificates SET slot = :slot WHERE site_id = :site AND fingerprint = :fp")
                .param("slot", slot).param("site", siteId).param("fp", fingerprint).update();
    }

    public void audit(String siteId, String actorId, String actorName, String action, String fingerprint, Map<String, ?> details) {
        jdbc.sql("""
                        INSERT INTO sdp.site_audit_events (site_id, occurred_at, actor_id, actor_name, action, fingerprint, details)
                        VALUES (:site, :at, :actor, :actorName, :action, :fp, CAST(:details AS jsonb))""")
                .param("site", siteId).param("at", utc(clock.instant())).param("actor", actorId).param("actorName", actorName)
                .param("action", action).param("fp", fingerprint).param("details", json.writeValueAsString(details))
                .update();
    }

    public List<AuditEvent> auditEvents(String siteId, int limit) {
        return jdbc.sql("SELECT * FROM sdp.site_audit_events WHERE site_id = :site ORDER BY occurred_at DESC, id DESC LIMIT :limit")
                .param("site", siteId).param("limit", limit)
                .query((rs, i) -> new AuditEvent(rs.getLong("id"), rs.getString("site_id"), instant(rs, "occurred_at"),
                        rs.getString("actor_id"), rs.getString("actor_name"), rs.getString("action"),
                        rs.getString("fingerprint"), json.readValue(rs.getString("details"), OBJECT)))
                .list();
    }

    // --- Devices and operations -----------------------------------------------------------------------------

    public List<String> deviceIds(String siteId) {
        return jdbc.sql("SELECT device_id FROM sdp.devices WHERE site_id = :site ORDER BY device_id")
                .param("site", siteId).query(String.class).list();
    }

    public List<String> devicesDisabledByPlatform(String siteId) {
        return jdbc.sql("SELECT device_id FROM sdp.devices WHERE site_id = :site AND disabled_by_platform ORDER BY device_id")
                .param("site", siteId).query(String.class).list();
    }

    public void markDisabledByPlatform(String deviceId, boolean disabled) {
        jdbc.sql("UPDATE sdp.devices SET disabled_by_platform = :flag WHERE device_id = :id")
                .param("flag", disabled).param("id", deviceId).update();
    }

    public DeviceOperation createOperation(String siteId, String kind, int deviceCount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO sdp.site_operations (id, site_id, kind, status, device_count, created_at, updated_at)
                        VALUES (:id, :site, :kind, 'PENDING', :count, :now, :now)""")
                .param("id", id).param("site", siteId).param("kind", kind).param("count", deviceCount)
                .param("now", utc(clock.instant())).update();
        return operation(id).orElseThrow();
    }

    public Optional<DeviceOperation> operation(UUID id) {
        return jdbc.sql("SELECT * FROM sdp.site_operations WHERE id = :id").param("id", id).query(this::mapOperation).optional();
    }

    public List<DeviceOperation> pendingOperations() {
        return jdbc.sql("SELECT * FROM sdp.site_operations WHERE status = 'PENDING' ORDER BY created_at")
                .query(this::mapOperation).list();
    }

    /** Other pending operations of the site are superseded by a newer one (e.g. re-enable after disable). */
    public void supersedePending(String siteId, UUID keep) {
        jdbc.sql("""
                        UPDATE sdp.site_operations SET status = 'DONE', last_error = 'superseded', updated_at = :now
                         WHERE site_id = :site AND status = 'PENDING' AND id <> :keep""")
                .param("site", siteId).param("keep", keep).param("now", utc(clock.instant())).update();
    }

    public void finishOperation(UUID id, String status, String error) {
        jdbc.sql("""
                        UPDATE sdp.site_operations SET status = :status, attempts = attempts + 1, last_error = :error, updated_at = :now
                         WHERE id = :id""")
                .param("status", status).param("error", error).param("now", utc(clock.instant())).param("id", id).update();
    }

    // --- Mapping --------------------------------------------------------------------------------------------

    private SiteCertificate certificate(ResultSet rs, int row) throws SQLException {
        Instant notAfter = instant(rs, "not_after");
        return new SiteCertificate(rs.getString("site_id"), rs.getString("fingerprint"), rs.getString("slot"),
                rs.getString("subject"), rs.getString("issuer"), instant(rs, "not_before"), notAfter,
                status(notAfter, clock.instant()), rs.getString("added_by"), rs.getString("added_by_name"),
                instant(rs, "added_at"), rs.getString("dps_certificate_name"));
    }

    static String status(Instant notAfter, Instant now) {
        if (!notAfter.isAfter(now)) {
            return "expired";
        }
        return notAfter.isBefore(now.plus(EXPIRING_WITHIN)) ? "expiring" : "valid";
    }

    private DeviceOperation mapOperation(ResultSet rs, int row) throws SQLException {
        return new DeviceOperation(rs.getObject("id", UUID.class), rs.getString("site_id"), rs.getString("kind"),
                rs.getString("status"), rs.getInt("device_count"), rs.getInt("attempts"), rs.getString("last_error"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
