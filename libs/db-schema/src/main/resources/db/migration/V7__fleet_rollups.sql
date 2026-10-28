-- M6: hourly per-device metric rollups for fleet summaries (spec fleet-analytics). The maintenance task rolls up every
-- hour that ended before the ingestion watermark; the device API combines rollups with the raw measurements of the
-- range edges and of the hours not rolled up yet.

CREATE TABLE sdp.metric_rollups_hourly (
    metric       text        NOT NULL,
    hour         timestamptz NOT NULL,
    device_id    text        NOT NULL,
    site_id      text        NOT NULL,
    min_value    float8      NOT NULL,
    max_value    float8      NOT NULL,
    sum_value    float8      NOT NULL,
    sample_count bigint      NOT NULL,
    first_at     timestamptz NOT NULL,
    first_value  float8      NOT NULL,
    last_at      timestamptz NOT NULL,
    last_value   float8      NOT NULL,
    PRIMARY KEY (metric, hour, device_id)
);

-- Every hour before `rolled_until` is rolled up (NULL: nothing yet).
CREATE TABLE sdp.metric_rollup_state (
    singleton    boolean     PRIMARY KEY DEFAULT true CHECK (singleton),
    rolled_until timestamptz,
    updated_at   timestamptz NOT NULL DEFAULT now()
);
INSERT INTO sdp.metric_rollup_state (singleton, rolled_until) VALUES (true, NULL);

-- Range edges are read by time across all devices; rows arrive in time order, so a BRIN index stays small and
-- effective (created on every partition, including future ones).
CREATE INDEX measurements_measured_at_brin ON sdp.measurements USING brin (measured_at);

GRANT SELECT ON sdp.metric_rollups_hourly, sdp.metric_rollup_state TO sdp_catalog;
