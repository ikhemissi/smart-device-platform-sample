-- M5: threshold alerting (ADR-0009). Thresholds are managed through the device API; the alert service evaluates
-- measurements against them, records alerts and queues their notifications (transactional outbox).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdp_alerting') THEN
        CREATE ROLE sdp_alerting NOLOGIN;
    END IF;
END
$$;

-- Expected range of one metric of a device, and who to email when a measurement leaves it.
CREATE TABLE sdp.alert_thresholds (
    id              uuid        PRIMARY KEY,
    device_id       text        NOT NULL REFERENCES sdp.devices (device_id),
    metric          text        NOT NULL,
    min_value       numeric,
    max_value       numeric,
    recipients      text[]      NOT NULL CHECK (cardinality(recipients) BETWEEN 1 AND 10),
    enabled         boolean     NOT NULL DEFAULT true,
    version         integer     NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      text        NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text        NOT NULL,
    updated_by_name text,
    CHECK (min_value IS NOT NULL OR max_value IS NOT NULL),
    CHECK (min_value IS NULL OR max_value IS NULL OR min_value < max_value)
);
CREATE INDEX alert_thresholds_device_idx ON sdp.alert_thresholds (device_id);

-- One row per threshold, created with it: evaluation locks it (SELECT ... FOR UPDATE), which serialises replicas even
-- before any alert exists, and remembers the last measurement evaluated (out-of-order and duplicate deliveries).
CREATE TABLE sdp.alert_threshold_state (
    threshold_id      uuid        PRIMARY KEY REFERENCES sdp.alert_thresholds (id) ON DELETE CASCADE,
    last_evaluated_at timestamptz,
    last_message_key  text
);

-- Alerts: one per breach episode of a threshold, with a snapshot of the threshold when it opened.
CREATE TABLE sdp.alert_events (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    threshold_id     uuid        REFERENCES sdp.alert_thresholds (id) ON DELETE SET NULL,
    device_id        text        NOT NULL,
    site_id          text        NOT NULL,
    metric           text        NOT NULL,
    threshold_min    numeric,
    threshold_max    numeric,
    recipients       text[]      NOT NULL,
    status           text        NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    direction        text        NOT NULL CHECK (direction IN ('ABOVE', 'BELOW')),
    opened_at        timestamptz NOT NULL,
    closed_at        timestamptz,
    first_value      numeric     NOT NULL,
    worst_value      numeric     NOT NULL,
    last_value       numeric     NOT NULL,
    last_breach_at   timestamptz NOT NULL,
    last_notified_at timestamptz
);
-- At most one open alert per threshold.
CREATE UNIQUE INDEX alert_events_one_open_idx ON sdp.alert_events (threshold_id) WHERE status = 'OPEN';
CREATE INDEX alert_events_device_idx ON sdp.alert_events (device_id, opened_at DESC);
CREATE INDEX alert_events_site_idx ON sdp.alert_events (site_id, status, opened_at DESC);

-- Emails to send (outbox), written in the alert's transaction and sent with retries by the alert service.
CREATE TABLE sdp.alert_notifications (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id        bigint      NOT NULL REFERENCES sdp.alert_events (id),
    kind            text        NOT NULL CHECK (kind IN ('ALERT', 'REMINDER', 'RESOLVED')),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    recipients      text[]      NOT NULL,
    value           numeric,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz
);
CREATE INDEX alert_notifications_pending_idx ON sdp.alert_notifications (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX alert_notifications_alert_idx ON sdp.alert_notifications (alert_id);

-- Device API (sdp_catalog): manages thresholds; reads alerts; closes (cancels) the open alert of a threshold that is
-- disabled or deleted.
GRANT SELECT, INSERT, UPDATE, DELETE ON sdp.alert_thresholds TO sdp_catalog;
GRANT SELECT, INSERT, UPDATE ON sdp.alert_threshold_state TO sdp_catalog;
GRANT SELECT ON sdp.alert_events, sdp.alert_notifications TO sdp_catalog;
GRANT UPDATE (status, closed_at) ON sdp.alert_events TO sdp_catalog;

-- Alert service (sdp_alerting): reads thresholds and device context, manages alerts and their notifications.
GRANT USAGE ON SCHEMA sdp TO sdp_alerting;
GRANT SELECT ON sdp.alert_thresholds, sdp.sites, sdp.metric_definitions TO sdp_alerting;
GRANT SELECT (device_id, site_id, display_name) ON sdp.devices TO sdp_alerting;
GRANT SELECT, UPDATE ON sdp.alert_threshold_state TO sdp_alerting;
GRANT SELECT, INSERT, UPDATE ON sdp.alert_events, sdp.alert_notifications TO sdp_alerting;
