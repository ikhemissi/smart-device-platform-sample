-- M1 core schema (ADR-0004, implementation guidelines §3).
-- Owned by the migrator identity. Application identities get privileges through NOLOGIN group roles;
-- principals are created and granted membership in afterMigrate.sql.

CREATE SCHEMA IF NOT EXISTS sdp;
CREATE SCHEMA IF NOT EXISTS partman;
CREATE EXTENSION IF NOT EXISTS pg_partman SCHEMA partman;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdp_ingest') THEN
        CREATE ROLE sdp_ingest NOLOGIN;
    END IF;
END
$$;

-- Reference data ------------------------------------------------------------------------------------------

CREATE TABLE sdp.sites (
    site_id      text        PRIMARY KEY CHECK (site_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(site_id) BETWEEN 3 AND 40),
    display_name text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sdp.devices (
    device_id           text        PRIMARY KEY,
    site_id             text        NOT NULL REFERENCES sdp.sites (site_id),
    display_name        text,
    description         text,
    location            jsonb,
    last_payload_format text        NOT NULL,
    first_seen_at       timestamptz NOT NULL,
    last_seen_at        timestamptz NOT NULL,
    updated_at          timestamptz,
    updated_by          text
);
CREATE INDEX devices_site_idx ON sdp.devices (site_id);

CREATE TABLE sdp.metric_definitions (
    name        text PRIMARY KEY,
    unit        text NOT NULL,
    description text NOT NULL
);

-- Telemetry (time-partitioned, one partition per day) ----------------------------------------------------

CREATE TABLE sdp.measurements (
    device_id      text        NOT NULL,
    site_id        text        NOT NULL,
    measured_at    timestamptz NOT NULL,
    received_at    timestamptz NOT NULL,
    message_key    text        NOT NULL,
    payload_format text        NOT NULL,
    raw_payload    bytea       NOT NULL,
    metrics        jsonb       NOT NULL,
    attributes     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (device_id, measured_at, message_key)
) PARTITION BY RANGE (measured_at);

CREATE TABLE sdp.rejected_messages (
    received_at  timestamptz NOT NULL,
    enqueued_at  timestamptz,
    device_id    text,
    site_id      text,
    message_key  text        NOT NULL,
    reason       text        NOT NULL,
    detail       text,
    raw_payload  bytea
) PARTITION BY RANGE (received_at);
CREATE INDEX rejected_messages_received_idx ON sdp.rejected_messages (received_at);
CREATE INDEX rejected_messages_device_idx ON sdp.rejected_messages (device_id, received_at);

SELECT partman.create_parent(p_parent_table => 'sdp.measurements', p_control => 'measured_at', p_interval => '1 day', p_premake => 7);
SELECT partman.create_parent(p_parent_table => 'sdp.rejected_messages', p_control => 'received_at', p_interval => '1 day', p_premake => 7);

-- Retention values are (re)applied by the migrator's maintenance task from its configuration.
UPDATE partman.part_config
   SET infinite_time_partitions = true,
       retention = '30 days',
       retention_keep_table = false
 WHERE parent_table IN ('sdp.measurements', 'sdp.rejected_messages');

-- Deduplication ledger (not partitioned so (device_id, message_key) can be unique across time) ------------

CREATE TABLE sdp.ingested_messages (
    device_id   text        NOT NULL,
    message_key text        NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, message_key)
);
CREATE INDEX ingested_messages_ingested_idx ON sdp.ingested_messages (ingested_at);

-- Privileges of the ingestion role (telemetry decoder) ----------------------------------------------------

GRANT USAGE ON SCHEMA sdp TO sdp_ingest;
GRANT SELECT, INSERT ON sdp.sites TO sdp_ingest;
GRANT SELECT, INSERT ON sdp.devices TO sdp_ingest;
GRANT UPDATE (last_seen_at, last_payload_format) ON sdp.devices TO sdp_ingest;
GRANT INSERT ON sdp.measurements TO sdp_ingest;
GRANT INSERT ON sdp.rejected_messages TO sdp_ingest;
GRANT SELECT (device_id, message_key), INSERT ON sdp.ingested_messages TO sdp_ingest;
