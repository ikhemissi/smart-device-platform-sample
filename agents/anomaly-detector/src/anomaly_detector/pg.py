"""PostgreSQL source and store of the live cycle (role sdp_insights, migration V8)."""

from dataclasses import dataclass, fields
from datetime import datetime, timedelta

import psycopg
from psycopg.types.json import Jsonb

from . import db
from .config import Settings
from .cycle import Anomaly
from .db import Device
from .detection import Baseline, Candidate, Reading, build_baselines

COLUMNS = [f.name for f in fields(Anomaly)]
# Retries stop after a day: older pending anomalies stay pending (visible as such) instead of being re-sent forever.
RETRY_PERIOD = timedelta(days=1)


def _anomaly(row: dict) -> Anomaly:
    return Anomaly(**{name: row[name] for name in COLUMNS})


@dataclass
class PgSource:
    conn: psycopg.Connection
    settings: Settings

    def devices(self) -> list[Device]:
        return db.analysed_devices(self.conn, self.settings.sites)

    def units(self) -> dict[str, str]:
        return db.metric_units(self.conn)

    def baselines(self, until: datetime) -> dict[tuple[str, str], Baseline]:
        return build_baselines(db.baseline_rollups(self.conn, self.settings.sites, self.settings.metrics, until))

    def readings(self, until: datetime) -> list[Reading]:
        return db.window_readings(self.conn, self.settings.sites, self.settings.metrics, until)


@dataclass
class PgStore:
    conn: psycopg.Connection
    sites: list[str]

    def recent(self, since: datetime) -> list[Anomaly]:
        rows = self.conn.execute(
            f"SELECT {', '.join(COLUMNS)} FROM sdp.anomalies WHERE site_id = ANY(%s) AND ended_at >= %s",
            (self.sites, since),
        ).fetchall()
        return [_anomaly(row) for row in rows]

    def add(self, anomaly: Anomaly) -> Anomaly:
        values = {name: getattr(anomaly, name) for name in COLUMNS if name != "id"}
        values["state"] = Jsonb(values["state"])
        values["probabilities"] = Jsonb(values["probabilities"]) if values["probabilities"] is not None else None
        anomaly.id = self.conn.execute(
            f"INSERT INTO sdp.anomalies ({', '.join(values)}) VALUES ({', '.join(['%s'] * len(values))}) RETURNING id",
            list(values.values()),
        ).fetchone()["id"]
        return anomaly

    def extend(self, anomaly: Anomaly, candidate: Candidate) -> None:
        """The episode now ends with the candidate; its statistics cover all its readings, its score the largest."""
        a = candidate.assessment
        self.conn.execute(
            """UPDATE sdp.anomalies AS x
                  SET ended_at = greatest(x.ended_at, %(ended_at)s),
                      (observed_min, observed_max, observed_mean) = (
                        SELECT least(min(v), x.observed_min, %(min)s), greatest(max(v), x.observed_max, %(max)s),
                               coalesce(avg(v), x.observed_mean)
                          FROM (SELECT (m.metrics ->> x.metric)::float8 AS v FROM sdp.measurements m
                                 WHERE m.device_id = x.device_id AND m.measured_at BETWEEN x.started_at AND %(ended_at)s
                                   AND jsonb_typeof(m.metrics -> x.metric) = 'number') AS readings),
                      observed_last = %(last)s,
                      score = CASE WHEN abs(%(score)s) > abs(x.score) THEN %(score)s ELSE x.score END,
                      updated_at = now()
                WHERE id = %(id)s""",
            {"ended_at": a.ended_at, "min": a.observed_min, "max": a.observed_max, "last": a.observed_last,
             "score": candidate.score, "id": anomaly.id},
        )  # fmt: skip
        anomaly.ended_at = max(anomaly.ended_at, a.ended_at)

    def unfinished(self, limit: int) -> list[Anomaly]:
        rows = self.conn.execute(
            f"""SELECT {", ".join(COLUMNS)} FROM sdp.anomalies
                 WHERE site_id = ANY(%s) AND detected_at > now() - %s
                   AND (decision = 'pending' OR (decision = 'confirmed' AND explanation IS NULL))
                 ORDER BY detected_at LIMIT %s""",
            (self.sites, RETRY_PERIOD, limit),
        ).fetchall()
        return [_anomaly(row) for row in rows]

    def record_decision(self, anomaly: Anomaly) -> None:
        self.conn.execute(
            """UPDATE sdp.anomalies SET decision = %s, classification = %s, probabilities = %s,
                      confidence_threshold = %s, decision_model = %s, decision_model_version = %s, updated_at = now()
                WHERE id = %s""",
            (anomaly.decision, anomaly.classification, Jsonb(anomaly.probabilities), anomaly.confidence_threshold,
             anomaly.decision_model, anomaly.decision_model_version, anomaly.id),
        )  # fmt: skip

    def record_explanation(self, anomaly: Anomaly) -> None:
        self.conn.execute(
            "UPDATE sdp.anomalies SET explanation = %s, explanation_model = %s, updated_at = now() WHERE id = %s",
            (anomaly.explanation, anomaly.explanation_model, anomaly.id),
        )
