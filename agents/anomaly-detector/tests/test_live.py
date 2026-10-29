"""Checks against the dev environment (`uv run pytest -m live`, .env from scripts/anomaly-env.sh)."""

import pytest
from azure.identity import DefaultAzureCredential

from anomaly_detector import db
from anomaly_detector.config import Settings


@pytest.mark.live
def test_reads_analysed_devices():
    settings = Settings()
    with db.connect(settings, DefaultAzureCredential(process_timeout=60)) as conn:
        devices = db.analysed_devices(conn, settings.sites)
    assert devices
    assert {d.site_id for d in devices} <= set(settings.sites)


@pytest.mark.live
def test_store_round_trip_is_rolled_back():
    from dataclasses import replace
    from datetime import UTC, datetime, timedelta

    from anomaly_detector.cycle import Anomaly
    from anomaly_detector.detection import Assessment, Candidate
    from anomaly_detector.pg import PgStore

    settings = Settings()
    with db.connect(settings, DefaultAzureCredential(process_timeout=60)) as conn:
        conn.autocommit = False
        try:
            device = db.analysed_devices(conn, settings.sites)[0]
            now = datetime.now(UTC)
            store = PgStore(conn, settings.sites)
            a = Assessment(device.device_id, "temperature", now - timedelta(minutes=30), now - timedelta(minutes=15),
                           30, 30.0, 35.0, 33.0, 35.0, 21.0, 0.2, 60.0, 10.0, False)  # fmt: skip
            anomaly = store.add(
                Anomaly.from_candidate(Candidate("deviation", a), device.site_id, {"device": {"id": "x"}})
            )
            assert anomaly.id is not None
            assert any(r.id == anomaly.id for r in store.recent(now - timedelta(minutes=30)))
            assert any(r.id == anomaly.id for r in store.unfinished(100))

            later = replace(a, started_at=now - timedelta(minutes=15), ended_at=now, observed_max=36.0, score=-70.0)
            store.extend(anomaly, Candidate("deviation", later))
            anomaly.decision, anomaly.classification = "confirmed", "real_event"
            anomaly.probabilities = {
                "real_event": 0.9,
                "sensor_fault": 0.1,
                "expected_pattern": 0.0,
                "cannot_tell": 0.0,
            }
            anomaly.confidence_threshold, anomaly.decision_model, anomaly.decision_model_version = 0.7, "m", "1"
            store.record_decision(anomaly)
            anomaly.explanation, anomaly.explanation_model = "Explained.", "gpt-6-luna"
            store.record_explanation(anomaly)

            row = conn.execute("SELECT * FROM sdp.anomalies WHERE id = %s", (anomaly.id,)).fetchone()
            assert row["ended_at"] == later.ended_at and row["observed_max"] == 36.0 and row["score"] == -70.0
            # The mean covers the real readings of the whole episode (the extension recomputes it).
            assert row["observed_mean"] != 33.0
            assert (row["decision"], row["classification"], row["explanation"]) == (
                "confirmed",
                "real_event",
                "Explained.",
            )
            assert row["probabilities"]["real_event"] == 0.9
            assert not any(r.id == anomaly.id for r in store.unfinished(100))
        finally:
            conn.rollback()
