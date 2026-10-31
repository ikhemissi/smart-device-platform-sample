"""One detection cycle (M7 design D2-D4, D10): detect candidates, extend running episodes, classify new candidates,
explain confirmed ones, then retry what a previous cycle left unfinished (pending classification, missing
explanation). The same logic runs live (PostgreSQL) and in dry runs (replayed data, in-memory store, no model call).
"""

import logging
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Protocol

from opentelemetry.trace import Status, StatusCode

from .config import Settings
from .db import Device
from .decision import Classification, Classifier, build_state, decide
from .detection import Baseline, Candidate, Reading, continues, detect
from .explanation import Explanation
from .telemetry import job_span

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
            device_id=a.device_id,
            site_id=site_id,
            metric=a.metric,
            kind=candidate.kind,
            started_at=a.started_at,
            ended_at=a.ended_at,
            observed_min=a.observed_min,
            observed_max=a.observed_max,
            observed_mean=a.observed_mean,
            observed_last=a.observed_last,
            usual_value=a.usual_value,
            spread=a.spread,
            score=candidate.score,
            state=state,
        )


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
        latest = _latest_by_series(recent)
        created: set[int] = set()
        for candidate in candidates:
            a = candidate.assessment
            episode = latest.get((a.device_id, a.metric))
            if episode is not None and continues(episode.ended_at, candidate):
                self.store.extend(episode, candidate)
                result.extended += 1
                continue
            anomaly = self._create(candidate, devices, units, recent, result)
            latest[(a.device_id, a.metric)] = anomaly
            if anomaly.id is not None:
                created.add(anomaly.id)
            self._classify(anomaly, result)

        if self.classifier is not None:
            self._retry_unfinished(created, result)
        return result

    def _create(
        self,
        candidate: Candidate,
        devices: dict[str, Device],
        units: dict[str, str],
        recent: list[Anomaly],
        result: CycleResult,
    ) -> Anomaly:
        """A new episode, stored with the state the models will see."""
        a = candidate.assessment
        device = devices[a.device_id]
        site_devices = [d.device_id for d in devices.values() if d.site_id == device.site_id]
        open_anomalies = _other_open_anomalies(recent, a.device_id, a.metric)
        state = build_state(candidate, device, units.get(a.metric, ""), site_devices, open_anomalies)
        anomaly = self.store.add(Anomaly.from_candidate(candidate, device.site_id, state))
        result.created += 1
        result.kinds[candidate.kind] += 1
        return anomaly

    def _retry_unfinished(self, created: set[int], result: CycleResult) -> None:
        """What previous cycles left unfinished: a pending classification or a missing explanation."""
        for anomaly in self.store.unfinished(MAX_RETRIES_PER_CYCLE):
            if anomaly.id in created:
                continue
            result.retried += 1
            if anomaly.decision == "pending":
                self._classify(anomaly, result)
            elif anomaly.decision == "confirmed" and anomaly.explanation is None and anomaly.probabilities:
                classification = Classification(anomaly.probabilities, anomaly.decision_model or "")
                self._explain(anomaly, classification, result)

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


def _latest_by_series(anomalies: list[Anomaly]) -> dict[tuple[str, str], Anomaly]:
    """The most recently ended anomaly of each device and metric."""
    latest: dict[tuple[str, str], Anomaly] = {}
    for anomaly in sorted(anomalies, key=lambda a: a.ended_at):
        latest[(anomaly.device_id, anomaly.metric)] = anomaly
    return latest


def _other_open_anomalies(recent: list[Anomaly], device_id: str, metric: str) -> list[dict[str, Any]]:
    """The device's recent anomalies on its other metrics, as context for the models."""
    return [
        {"metric": o.metric, "kind": o.kind, "since": o.started_at.isoformat(), "classification": o.classification}
        for o in recent
        if o.device_id == device_id and o.metric != metric
    ]


COUNTS = ("devices", "assessed", "candidates", "created", "extended", "retried", "explained", "errors")


def traced_cycle(sites: list[str], execute: Callable[[], CycleResult]) -> CycleResult:
    """A live cycle as one Application Insights request (SERVER span), with its counts as attributes. `execute` connects
    and runs the cycle, so a database or model failure at any step shows as a failed request."""
    with job_span("sdp.anomaly.cycle") as span:
        span.set_attribute("sdp.anomaly.sites", sites)
        result = execute()
        for name in COUNTS:
            span.set_attribute(f"sdp.anomaly.{name}", getattr(result, name))
        for status, count in result.decisions.items():
            span.set_attribute(f"sdp.anomaly.decisions.{status}", count)
        if result.errors:
            span.set_status(Status(StatusCode.ERROR, f"{result.errors} model call(s) failed"))
        return result
