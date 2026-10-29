-- M7: anomaly detection (ADR-0011). The anomaly detector (role sdp_insights) reads measurements and rollups and records
-- every classified candidate with its probabilities; the device API (sdp_catalog) lists anomalies and records reviews.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sdp_insights') THEN
        CREATE ROLE sdp_insights NOLOGIN;
    END IF;
END
$$;

-- One anomaly per device, metric and episode. `decision` is the automated outcome; a review (by an Editor) is recorded
-- alongside it without changing it.
CREATE TABLE sdp.anomalies (
    id                     bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id              text        NOT NULL,
    site_id                text        NOT NULL,
    metric                 text        NOT NULL,
    kind                   text        NOT NULL CHECK (kind IN ('deviation', 'flat', 'jump')),
    started_at             timestamptz NOT NULL,
    ended_at               timestamptz NOT NULL CHECK (ended_at >= started_at),
    observed_min           double precision NOT NULL,
    observed_max           double precision NOT NULL,
    observed_mean          double precision NOT NULL,
    observed_last          double precision NOT NULL,
    usual_value            double precision NOT NULL,
    spread                 double precision NOT NULL,
    score                  double precision NOT NULL,
    decision               text        NOT NULL CHECK (decision IN ('pending', 'confirmed', 'to_review', 'dismissed')),
    classification         text        CHECK (classification IN ('real_event', 'sensor_fault', 'expected_pattern', 'cannot_tell')),
    probabilities          jsonb,
    confidence_threshold   double precision,
    decision_model         text,
    decision_model_version text,
    state                  jsonb       NOT NULL,
    explanation            text,
    explanation_model      text,
    detected_at            timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    reviewed_at            timestamptz,
    reviewed_by            text,
    review_verdict         text        CHECK (review_verdict IN ('correct', 'incorrect')),
    review_comment         text        CHECK (length(review_comment) <= 1000),
    CHECK ((decision = 'pending') = (classification IS NULL))
);
CREATE INDEX anomalies_site_idx ON sdp.anomalies (site_id, started_at DESC);
CREATE INDEX anomalies_device_idx ON sdp.anomalies (device_id, metric, ended_at DESC);
-- Candidates waiting for a classification (retried by the next cycle).
CREATE INDEX anomalies_pending_idx ON sdp.anomalies (detected_at) WHERE decision = 'pending';

GRANT USAGE ON SCHEMA sdp TO sdp_insights;
GRANT SELECT ON sdp.measurements, sdp.metric_rollups_hourly, sdp.devices, sdp.sites, sdp.metric_definitions TO sdp_insights;
GRANT SELECT, INSERT, UPDATE ON sdp.anomalies TO sdp_insights;

GRANT SELECT ON sdp.anomalies TO sdp_catalog;
GRANT UPDATE (reviewed_at, reviewed_by, review_verdict, review_comment) ON sdp.anomalies TO sdp_catalog;
