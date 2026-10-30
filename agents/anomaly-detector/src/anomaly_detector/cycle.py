"""One detection cycle (M7 design D2-D4, D10): detect candidates, extend running episodes, classify new candidates,
explain confirmed ones, then retry what a previous cycle left unfinished (pending classification, missing
explanation). The same logic runs live (PostgreSQL) and in dry runs (replayed data, in-memory store, no model call).
"""

import logging
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Protocol

from opentelemetry.trace import SpanKind, Status, StatusCode

from .config import Settings
from .db import Device
from .decision import Classification, Classifier, build_state, decide
from .detection import Baseline, Candidate, Reading, continues, detect
from .explanation import Explanation
from .telemetry import tracer

log = logging.getLogger(__name__)

# Anomalies that ended within this period are candidates for extension and listed as the device's open anomalies.
RECENT = timedelta(minutes=30)
MAX_RETRIES_PER_CYCLE = 20


@dataclass
class Anomaly:
    device_id: str
    site_id: str
    metric: str
    kind: str
    started_at: datetime
    ended_at: datetime
    observed_min: float
    observed_max: float
    observed_mean: float
    observed_last: float
    usual_value: float
    spread: float
    score: float
    state: dict[str, Any]
    decision: str = "pending"
    classification: str | None = None
    probabilities: dict[str, float] | None = None
    confidence_threshold: float | None = None
    decision_model: str | None = None
    decision_model_version: str | None = None
    explanation: str | None = None
    explanation_model: str | None = None
    id: int | None = None

    @classmethod
    def from_candidate(cls, candidate: Candidate, site_id: str, state: dict[str, Any]) -> "Anomaly":
        a = candidate.assessment
        return cls(
            a.device_id, site_id, a.metric, candidate.kind, a.started_at, a.ended_at, a.observed_min, a.observed_max,
            a.observed_mean, a.observed_last, a.usual_value, a.spread, candidate.score, state,
        )  # fmt: skip


class Source(Protocol):
    def devices(self) -> list[Device]: ...
    def units(self) -> dict[str, str]: ...
    def baselines(self, until: datetime) -> dict[tuple[str, str], Baseline]: ...
    def readings(self, until: datetime) -> list[Reading]: ...


class Store(Protocol):
    def recent(self, since: datetime) -> list[Anomaly]: ...
    def add(self, anomaly: Anomaly) -> Anomaly: ...
    def extend(self, anomaly: Anomaly, candidate: Candidate) -> None: ...
    def unfinished(self, limit: int) -> list[Anomaly]: ...
    def record_decision(self, anomaly: Anomaly) -> None: ...
    def record_explanation(self, anomaly: Anomaly) -> None: ...


class Explains(Protocol):
    def explain(self, state: dict[str, Any], classification: Classification) -> Explanation | None: ...


@dataclass
class CycleResult:
    devices: int = 0
    assessed: int = 0
    candidates: int = 0
    created: int = 0
    extended: int = 0
    retried: int = 0
    explained: int = 0
    errors: int = 0
    decisions: Counter = field(default_factory=Counter)
    kinds: Counter = field(default_factory=Counter)


@dataclass
class Detector:
    settings: Settings
    source: Source
    store: Store
    classifier: Classifier | None = None  # None: dry run, candidates stay pending
    explainer: Explains | None = None
    decision_model_version: str = "1"

    def run(self, now: datetime) -> CycleResult:
        result = CycleResult()
        devices = {d.device_id: d for d in self.source.devices()}
        units = self.source.units()
        readings = [r for r in self.source.readings(now) if r.device_id in devices]
        assessments, candidates = detect(readings, self.source.baselines(now), self.settings.deviation)
        result.devices, result.assessed, result.candidates = len(devices), len(assessments), len(candidates)

        recent = self.store.recent(now - RECENT)
        latest: dict[tuple[str, str], Anomaly] = {}
        for anomaly in sorted(recent, key=lambda a: a.ended_at):
            latest[(anomaly.device_id, anomaly.metric)] = anomaly

        handled: set[int] = set()
        for candidate in candidates:
            a = candidate.assessment
            episode = latest.get((a.device_id, a.metric))
            if episode is not None and continues(episode.ended_at, candidate):
                self.store.extend(episode, candidate)
                result.extended += 1
                continue
            open_anomalies = [
                {
                    "metric": o.metric,
                    "kind": o.kind,
                    "since": o.started_at.isoformat(),
                    "classification": o.classification,
                }
                for o in recent
                if o.device_id == a.device_id and o.metric != a.metric
            ]
            device = devices[a.device_id]
            site_devices = [d.device_id for d in devices.values() if d.site_id == device.site_id]
            state = build_state(candidate, device, units.get(a.metric, ""), site_devices, open_anomalies)
            anomaly = self.store.add(Anomaly.from_candidate(candidate, device.site_id, state))
            latest[(a.device_id, a.metric)] = anomaly
            result.created += 1
            result.kinds[candidate.kind] += 1
            if anomaly.id is not None:
                handled.add(anomaly.id)
            self._classify(anomaly, result)

        if self.classifier is not None:
            for anomaly in self.store.unfinished(MAX_RETRIES_PER_CYCLE):
                if anomaly.id in handled:
                    continue
                result.retried += 1
                if anomaly.decision == "pending":
                    self._classify(anomaly, result)
                elif anomaly.decision == "confirmed" and anomaly.explanation is None and anomaly.probabilities:
                    self._explain(anomaly, Classification(anomaly.probabilities, anomaly.decision_model or ""), result)
        return result

    def _classify(self, anomaly: Anomaly, result: CycleResult) -> None:
        if self.classifier is None:
            return
        decision = decide(self.classifier, anomaly.state, self.settings.confidence)
        if decision.classification is None:
            result.errors += 1
            return
        anomaly.decision = decision.status
        anomaly.classification = decision.label
        anomaly.probabilities = decision.classification.probabilities
        anomaly.confidence_threshold = self.settings.confidence
        anomaly.decision_model = decision.classification.model
        anomaly.decision_model_version = self.decision_model_version
        self.store.record_decision(anomaly)
        result.decisions[decision.status] += 1
        if decision.status == "confirmed":
            self._explain(anomaly, decision.classification, result)

    def _explain(self, anomaly: Anomaly, classification: Classification, result: CycleResult) -> None:
        if self.explainer is None:
            return
        explanation = self.explainer.explain(anomaly.state, classification)
        if explanation is None:
            result.errors += 1
            return
        anomaly.explanation, anomaly.explanation_model = explanation.text, explanation.model
        self.store.record_explanation(anomaly)
        result.explained += 1


def traced_cycle(detector: Detector, now: datetime) -> CycleResult:
    """A live cycle as one Application Insights request (SERVER span), with its counts as attributes."""
    with tracer.start_as_current_span("sdp.anomaly.cycle", kind=SpanKind.SERVER) as span:
        span.set_attribute("sdp.anomaly.sites", detector.settings.sites)
        try:
            result = detector.run(now)
        except Exception as error:
            span.record_exception(error)
            span.set_status(Status(StatusCode.ERROR, type(error).__name__))
            raise
        for name in ("devices", "assessed", "candidates", "created", "extended", "retried", "explained", "errors"):
            span.set_attribute(f"sdp.anomaly.{name}", getattr(result, name))
        for status, count in result.decisions.items():
            span.set_attribute(f"sdp.anomaly.decisions.{status}", count)
        if result.errors:
            span.set_status(Status(StatusCode.ERROR, f"{result.errors} model call(s) failed"))
        return result
