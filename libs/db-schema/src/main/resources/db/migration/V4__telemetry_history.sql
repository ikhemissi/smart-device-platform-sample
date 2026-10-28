-- M3: metrics reported per device, and ingestion watermarks used by the device API to cache history safely.

-- Metrics each device has reported, with the last time they were seen (maintained by the decoder; rows older than the
-- retention period are purged by the maintenance task). Keys are as stored in measurements.metrics, including
-- channel-suffixed Cayenne LPP keys such as temperature_ch2.
CREATE TABLE sdp.device_metrics (
    device_id    text        NOT NULL REFERENCES sdp.devices (device_id),
    metric       text        NOT NULL,
    last_seen_at timestamptz NOT NULL,
    PRIMARY KEY (device_id, metric)
);

-- Per Event Hubs partition: every message enqueued before `watermark` has been persisted. Partitions are registered
-- at -infinity when the decoder starts processing them; values only move forward.
CREATE TABLE sdp.ingestion_watermarks (
    partition_id text        PRIMARY KEY,
    watermark    timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- Backfill from retained measurements.
INSERT INTO sdp.device_metrics (device_id, metric, last_seen_at)
SELECT m.device_id, k.metric, max(m.measured_at)
  FROM sdp.measurements m
 CROSS JOIN LATERAL jsonb_object_keys(m.metrics) AS k (metric)
 GROUP BY m.device_id, k.metric;

GRANT SELECT, INSERT, UPDATE ON sdp.device_metrics, sdp.ingestion_watermarks TO sdp_ingest;
GRANT SELECT ON sdp.device_metrics, sdp.ingestion_watermarks TO sdp_catalog;
