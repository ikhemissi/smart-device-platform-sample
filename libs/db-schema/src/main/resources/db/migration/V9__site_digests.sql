-- M8: site digests (ADR-0011). The digest job (role sdp_insights, shared with the anomaly detector) computes each site's
-- figures and stores them with the brief written by the language model; the device API (sdp_catalog) shows the latest
-- digest and records Editors' regeneration requests, which the job serves.

CREATE TABLE sdp.site_digests (
    id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id            text        NOT NULL REFERENCES sdp.sites (site_id) ON DELETE CASCADE,
    trigger            text        NOT NULL CHECK (trigger IN ('scheduled', 'requested')),
    period_from        timestamptz NOT NULL,
    period_to          timestamptz NOT NULL CHECK (period_to > period_from),
    comparison_from    timestamptz NOT NULL CHECK (comparison_from < period_from),
    generated_at       timestamptz NOT NULL DEFAULT now(),
    figures            jsonb       NOT NULL,
    brief              text,
    brief_model        text,
    brief_generated_at timestamptz,
    requested_by       text,
    CHECK ((brief IS NULL) = (brief_generated_at IS NULL))
);
CREATE INDEX site_digests_site_idx ON sdp.site_digests (site_id, generated_at DESC);

CREATE TABLE sdp.site_digest_requests (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id      text        NOT NULL REFERENCES sdp.sites (site_id) ON DELETE CASCADE,
    requested_at timestamptz NOT NULL DEFAULT now(),
    requested_by text        NOT NULL,
    processed_at timestamptz,
    digest_id    bigint      REFERENCES sdp.site_digests (id) ON DELETE SET NULL
);
-- At most one pending request per site: a second request returns the pending one.
CREATE UNIQUE INDEX site_digest_requests_pending_idx ON sdp.site_digest_requests (site_id) WHERE processed_at IS NULL;

-- The digest job: the figures' sources (alerts without their recipients' email addresses, rejected messages without their
-- payload, site certificates) and the digest tables.
GRANT SELECT (id, threshold_id, device_id, site_id, metric, threshold_min, threshold_max, status, direction, opened_at,
              closed_at, first_value, worst_value, last_value, last_breach_at)
    ON sdp.alert_events TO sdp_insights;
GRANT SELECT (received_at, device_id, site_id, reason) ON sdp.rejected_messages TO sdp_insights;
GRANT SELECT (site_id, slot, subject, not_after) ON sdp.site_certificates TO sdp_insights;
GRANT SELECT, INSERT, UPDATE ON sdp.site_digests TO sdp_insights;
GRANT SELECT ON sdp.site_digest_requests TO sdp_insights;
GRANT UPDATE (processed_at, digest_id) ON sdp.site_digest_requests TO sdp_insights;

-- The device API: read digests, record requests.
GRANT SELECT ON sdp.site_digests TO sdp_catalog;
GRANT SELECT, INSERT ON sdp.site_digest_requests TO sdp_catalog;
