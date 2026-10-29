from dataclasses import replace
from datetime import UTC, datetime, timedelta

import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import SpanKind

from anomaly_detector.config import Settings
from anomaly_detector.cycle import Detector, traced_cycle
from anomaly_detector.db import Device
from anomaly_detector.decision import OPTIONS, Classification, DecisionError
from anomaly_detector.explanation import Explanation
from anomaly_detector.replay import MemoryStore, ReplaySource, dry_run

from .test_detection import DEVICE, WINDOW, rollups, series, temperature

NOW = datetime(2026, 10, 29, 15, 0, tzinfo=UTC)
DEVICES = [Device(DEVICE, "paris-01", "Cold room 1", "ruuvi-v5")]
EXPORTER = InMemorySpanExporter()


@pytest.fixture(scope="module", autouse=True)
def tracing():
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(EXPORTER))
    trace.set_tracer_provider(provider)


@pytest.fixture
def settings(monkeypatch):
    monkeypatch.chdir("/")
    for name, value in {
        "SDP_DB_HOST": "db", "SDP_DB_USER": "u", "SDP_DECISION_ENDPOINT": "https://d/", "AZURE_OPENAI_ENDPOINT": "https://o/",
        "SDP_ANOMALY_CONFIDENCE": "0.8",
    }.items():  # fmt: skip
        monkeypatch.setenv(name, value)
    return Settings()


def history_and_readings(warm_from=None, warm_until=None, days=8):
    """A week of history, then readings until NOW + 1 h; optionally 10 °C warmer over [warm_from, warm_until)."""
    import random

    rng = random.Random(3)
    history = series("temperature", temperature, NOW - timedelta(days=days), NOW - WINDOW, rng)
    recent = series("temperature", temperature, NOW - WINDOW, NOW + timedelta(hours=1), rng)
    if warm_from:
        recent = [replace(r, value=r.value + 10) if warm_from <= r.measured_at < warm_until else r for r in recent]
    return ReplaySource(DEVICES, {"temperature": "°C"}, rollups(history), recent)


class FakeClassifier:
    def __init__(self, *outcomes):
        self.outcomes, self.calls = list(outcomes), 0

    def classify(self, state):
        self.calls += 1
        outcome = self.outcomes.pop(0) if self.outcomes else {"real_event": 0.9}
        if isinstance(outcome, Exception):
            raise outcome
        return Classification(dict.fromkeys(OPTIONS, 0.0) | outcome, "microsoft-decision-1")


class FakeExplainer:
    def __init__(self, *outcomes):
        self.outcomes, self.calls = list(outcomes), 0

    def explain(self, state, classification):
        self.calls += 1
        outcome = self.outcomes.pop(0) if self.outcomes else "Explained."
        return Explanation(outcome, "gpt-6-luna") if outcome else None


def detector(settings, source, classifier=None, explainer=None):
    return Detector(settings, source, MemoryStore(), classifier, explainer)


def test_quiet_cycle_records_nothing(settings):
    d = detector(settings, history_and_readings(), FakeClassifier(), FakeExplainer())
    result = d.run(NOW)
    assert (result.devices, result.assessed, result.candidates, result.created) == (1, 1, 0, 0)
    assert d.store.anomalies == []


def test_new_candidate_is_classified_explained_then_extended(settings):
    source = history_and_readings(NOW - timedelta(minutes=5), NOW + timedelta(minutes=30))
    classifier, explainer = FakeClassifier({"real_event": 0.91}), FakeExplainer()
    d = detector(settings, source, classifier, explainer)

    first = d.run(NOW)
    assert (first.created, first.extended, dict(first.decisions), first.explained) == (1, 0, {"confirmed": 1}, 1)
    anomaly = d.store.anomalies[0]
    assert (anomaly.decision, anomaly.classification, anomaly.explanation) == ("confirmed", "real_event", "Explained.")
    assert anomaly.probabilities["real_event"] == 0.91
    assert anomaly.confidence_threshold == 0.8
    assert anomaly.state["device"]["id"] == DEVICE

    for minutes in (5, 10, 15, 20, 25, 30):
        later = d.run(NOW + timedelta(minutes=minutes))
        assert later.created == 0
    assert len(d.store.anomalies) == 1
    assert anomaly.ended_at > NOW + timedelta(minutes=25)
    assert classifier.calls == 1 and explainer.calls == 1


def test_new_episode_after_a_quiet_period(settings):
    source = history_and_readings(NOW - timedelta(minutes=5), NOW)
    d = detector(settings, source, FakeClassifier(), FakeExplainer())
    d.run(NOW)
    for minutes in range(5, 40, 5):
        d.run(NOW + timedelta(minutes=minutes))
    later = NOW + timedelta(minutes=40)
    warm = [replace(r, value=r.value + 10) if r.measured_at >= later else r for r in source.window_by_time]
    second = replace(source, window_by_time=warm)
    d.source = second
    assert d.run(NOW + timedelta(minutes=45)).created == 1
    assert len(d.store.anomalies) == 2


def test_failed_classification_is_pending_then_retried(settings):
    source = history_and_readings(NOW - timedelta(minutes=5), NOW + timedelta(hours=1))
    classifier = FakeClassifier(DecisionError("429"), {"sensor_fault": 0.95})
    d = detector(settings, source, classifier, FakeExplainer())

    first = d.run(NOW)
    assert (first.created, first.errors, d.store.anomalies[0].decision) == (1, 1, "pending")
    second = d.run(NOW + timedelta(minutes=5))
    assert (second.retried, dict(second.decisions), second.explained) == (1, {"confirmed": 1}, 1)
    assert d.store.anomalies[0].classification == "sensor_fault"


def test_failed_explanation_is_retried(settings):
    source = history_and_readings(NOW - timedelta(minutes=5), NOW + timedelta(hours=1))
    explainer = FakeExplainer(None, "Second try.")
    d = detector(settings, source, FakeClassifier(), explainer)
    assert d.run(NOW).errors == 1
    assert d.store.anomalies[0].explanation is None
    # The in-memory store only retries pending decisions; the live store also returns confirmed anomalies without
    # explanation, which the cycle then explains.
    d.store.unfinished = lambda limit: [a for a in d.store.anomalies if a.explanation is None]
    assert d.run(NOW + timedelta(minutes=5)).explained == 1
    assert d.store.anomalies[0].explanation == "Second try."


@pytest.mark.parametrize(
    "probabilities,status", [({"expected_pattern": 0.95}, "dismissed"), ({"cannot_tell": 0.9}, "to_review")]
)
def test_only_confirmed_anomalies_are_explained(settings, probabilities, status):
    source = history_and_readings(NOW - timedelta(minutes=5), NOW)
    explainer = FakeExplainer()
    d = detector(settings, source, FakeClassifier(probabilities), explainer)
    assert dict(d.run(NOW).decisions) == {status: 1}
    assert explainer.calls == 0


def test_dry_run_calls_no_model_and_counts_episodes(settings):
    source = history_and_readings(NOW + timedelta(minutes=10), NOW + timedelta(minutes=25))
    per_day = dry_run(settings, source, NOW, NOW + timedelta(hours=1))
    counts = per_day[NOW.date().isoformat()]
    assert counts["episodes"] == 1
    # The first cycle sees one warm reading at the edge of its window (a jump); later ones extend that episode.
    assert counts["episodes.jump"] == 1
    assert counts["candidates"] >= 3
    assert not any(key.startswith("decisions.") for key in counts)


def test_live_cycle_is_one_request_span_with_counts(settings):
    EXPORTER.clear()
    source = history_and_readings(NOW - timedelta(minutes=5), NOW)
    traced_cycle(detector(settings, source, FakeClassifier(), FakeExplainer()), NOW)
    spans = {s.name: s for s in EXPORTER.get_finished_spans()}
    cycle = spans["sdp.anomaly.cycle"]
    assert cycle.kind == SpanKind.SERVER
    assert cycle.attributes["sdp.anomaly.created"] == 1
    assert cycle.attributes["sdp.anomaly.decisions.confirmed"] == 1
    assert spans["sdp.anomaly.classify"].parent.span_id == cycle.context.span_id
    assert spans["sdp.anomaly.classify"].attributes["sdp.anomaly.p.real_event"] == 0.9
