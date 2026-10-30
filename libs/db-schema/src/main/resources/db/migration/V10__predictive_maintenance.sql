-- M9: battery forecast and maintenance queue (ADR-0011, change m9-maintenance design D7). The maintenance job (role
-- sdp_insights, shared with the anomaly detector and the digest job) records each device's latest assessment and keeps
-- the queue; the device API (sdp_catalog) lists them and records Editors' statuses.

-- Latest assessment per device: battery forecast (null when the device reports no battery), reporting signals and the
-- decision model's probability of an intervention within 7 days.
CREATE TABLE sdp.device_maintenance (
    device_id              text        PRIMARY KEY REFERENCES sdp.devices (device_id) ON DELETE CASCADE,
    site_id                text        NOT NULL,
    assessed_at            timestamptz NOT NULL,
    battery                jsonb,
    signals                jsonb       NOT NULL,
    probability            double precision NOT NULL CHECK (probability BETWEEN 0 AND 1),
    decision_model         text        NOT NULL,
    decision_model_version text        NOT NULL
);
CREATE INDEX device_maintenance_site_idx ON sdp.device_maintenance (site_id);

-- The queue and its history: `open` and `planned` items are in the queue; `done` (by an Editor) and `resolved` (by the
-- platform, when the probability falls below the confidence) close an item.
CREATE TABLE sdp.maintenance_items (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id         text        NOT NULL REFERENCES sdp.devices (device_id) ON DELETE CASCADE,
    site_id           text        NOT NULL,
    status            text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'planned', 'done', 'resolved')),
    probability       double precision NOT NULL CHECK (probability BETWEEN 0 AND 1),
    reasons           jsonb       NOT NULL,
    battery           jsonb,
    work_order        text,
    work_order_model  text,
    work_order_at     timestamptz,
    opened_at         timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    resolved_at       timestamptz,
    status_by         text,
    status_at         timestamptz,
    status_comment    text        CHECK (length(status_comment) <= 1000),
    CHECK ((work_order IS NULL) = (work_order_at IS NULL)),
    CHECK ((status = 'resolved') = (resolved_at IS NOT NULL))
);
-- At most one item in the queue per device.
CREATE UNIQUE INDEX maintenance_items_queued_idx ON sdp.maintenance_items (device_id) WHERE status IN ('open', 'planned');
CREATE INDEX maintenance_items_site_idx ON sdp.maintenance_items (site_id, status, probability DESC);
CREATE INDEX maintenance_items_device_idx ON sdp.maintenance_items (device_id, status_at DESC);

GRANT SELECT, INSERT, UPDATE ON sdp.device_maintenance, sdp.maintenance_items TO sdp_insights;

GRANT SELECT ON sdp.device_maintenance, sdp.maintenance_items TO sdp_catalog;
GRANT UPDATE (status, status_by, status_at, status_comment, updated_at) ON sdp.maintenance_items TO sdp_catalog;
