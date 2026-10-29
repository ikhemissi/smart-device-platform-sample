import pytest
from pydantic import ValidationError

from anomaly_detector.config import Settings

REQUIRED = {
    "SDP_DB_HOST": "psql.example",
    "SDP_DB_USER": "id-anomaly-detector",
    "SDP_DECISION_ENDPOINT": "https://ais.example/",
    "AZURE_OPENAI_ENDPOINT": "https://ais.openai.example/",
}


@pytest.fixture
def env(monkeypatch):
    monkeypatch.chdir("/")  # no .env file
    for name, value in REQUIRED.items():
        monkeypatch.setenv(name, value)
    return monkeypatch


def test_defaults(env):
    settings = Settings()
    assert settings.sites == ["paris-01"]
    assert settings.metrics == ["temperature", "humidity", "pressure", "co2", "illuminance"]
    assert settings.decision_deployment == "decision-1"
    assert settings.db_name == "sdp"


def test_lists_are_trimmed(env):
    env.setenv("SDP_ANOMALY_SITES", " paris-01, lyon-01 ,,")
    assert Settings().sites == ["paris-01", "lyon-01"]


@pytest.mark.parametrize("name,value", [("SDP_ANOMALY_CONFIDENCE", "1.5"), ("SDP_ANOMALY_DEVIATION", "0")])
def test_thresholds_are_validated(env, name, value):
    env.setenv(name, value)
    with pytest.raises(ValidationError):
        Settings()
