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
