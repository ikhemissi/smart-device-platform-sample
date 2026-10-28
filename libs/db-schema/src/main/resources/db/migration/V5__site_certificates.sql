-- M4: site certificate management (provisioning API, ADR-0006). DPS is the source of truth for trust; these tables
-- mirror it for listing, record who changed what, and track device disable/re-enable operations.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdp_provisioning') THEN
        CREATE ROLE sdp_provisioning NOLOGIN;
    END IF;
END
$$;

-- Certificates trusted for a site (at most one per slot). The certificate itself lives in DPS.
CREATE TABLE sdp.site_certificates (
    site_id              text        NOT NULL REFERENCES sdp.sites (site_id),
    fingerprint          text        NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    slot                 text        NOT NULL CHECK (slot IN ('primary', 'secondary')),
    subject              text        NOT NULL,
    issuer               text        NOT NULL,
    not_before           timestamptz NOT NULL,
    not_after            timestamptz NOT NULL,
    dps_certificate_name text        NOT NULL,
    added_by             text        NOT NULL,
    added_by_name        text,
    added_at             timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (site_id, fingerprint),
    UNIQUE (site_id, slot)
);

-- Who changed what and when (append-only for the service).
CREATE TABLE sdp.site_audit_events (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id       text        NOT NULL,
    occurred_at   timestamptz NOT NULL DEFAULT now(),
    actor_id      text        NOT NULL,
    actor_name    text,
    action        text        NOT NULL,
    fingerprint   text,
    details       jsonb       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX site_audit_events_site_idx ON sdp.site_audit_events (site_id, occurred_at DESC);

-- Device disable/re-enable operations, retried by reconciliation until done.
CREATE TABLE sdp.site_operations (
    id           uuid        PRIMARY KEY,
    site_id      text        NOT NULL REFERENCES sdp.sites (site_id),
    kind         text        NOT NULL CHECK (kind IN ('DISABLE_DEVICES', 'ENABLE_DEVICES')),
    status       text        NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    device_count integer     NOT NULL DEFAULT 0,
    attempts     integer     NOT NULL DEFAULT 0,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX site_operations_pending_idx ON sdp.site_operations (status) WHERE status = 'PENDING';

-- Devices disabled in IoT Hub because their site lost its last certificate (only these are re-enabled).
ALTER TABLE sdp.devices ADD COLUMN disabled_by_platform boolean NOT NULL DEFAULT false;

-- Privileges of the provisioning role (provisioning API).
GRANT USAGE ON SCHEMA sdp TO sdp_provisioning;
GRANT SELECT, INSERT ON sdp.sites TO sdp_provisioning;
GRANT UPDATE (display_name) ON sdp.sites TO sdp_provisioning;
GRANT SELECT (device_id, site_id, disabled_by_platform) ON sdp.devices TO sdp_provisioning;
GRANT UPDATE (disabled_by_platform) ON sdp.devices TO sdp_provisioning;
GRANT SELECT, INSERT, UPDATE, DELETE ON sdp.site_certificates TO sdp_provisioning;
GRANT SELECT, INSERT ON sdp.site_audit_events TO sdp_provisioning;
GRANT SELECT, INSERT, UPDATE ON sdp.site_operations TO sdp_provisioning;
