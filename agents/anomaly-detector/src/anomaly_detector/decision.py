"""Classification by Microsoft-Decision-1 (M7 design D3): one `choice` question per candidate, every option's
probability kept, status from the configured confidence. A failed call leaves the candidate pending for the next cycle.
"""

import logging
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx

from .db import Device
from .detection import Assessment, Candidate
from .telemetry import tracer

log = logging.getLogger(__name__)

QUESTION = "classification"
OPTIONS = {
    "real_event": "The environment around the device really changed (e.g. door open, cooling failure); "
    "readings are consistent with physics",
    "sensor_fault": "The sensor or device misbehaves (stuck value, impossible jump, value out of physical range, "
    "isolated from neighbours)",
    "expected_pattern": "Normal behaviour for this device given its usual daily variation",
    "cannot_tell": "The data is insufficient to decide",
}
INSTRUCTIONS = (
    "A statistical check flagged these recent readings of an IoT sensor as unusual compared with the device's own "
    "usual values at this time of day. What best explains them?"
)
ANOMALOUS = {"real_event", "sensor_fault"}


class DecisionError(Exception):
    """The decision API could not classify the candidate (network, throttling, server or response error)."""


@dataclass(frozen=True)
class Classification:
    probabilities: dict[str, float]
    model: str
    input_tokens: int = 0

    @property
    def top(self) -> tuple[str, float]:
        return max(self.probabilities.items(), key=lambda item: item[1])


class Classifier(Protocol):
    """Replaceable scorer (ADR-0011): the preview decision model today, possibly another model later."""

    def classify(self, state: dict[str, Any]) -> Classification: ...


@dataclass
class DecisionClient:
    endpoint: str
    deployment: str
    token: Callable[[], str]
    client: httpx.Client = field(default_factory=lambda: httpx.Client(timeout=30))

    def classify(self, state: dict[str, Any]) -> Classification:
        body = {
            "model": self.deployment,
            "state": state,
            "questions": {QUESTION: {"type": "choice", "instructions": INSTRUCTIONS, "criteria": OPTIONS}},
        }
        try:
            response = self.client.post(
                f"{self.endpoint.rstrip('/')}/providers/microsoft/v1/systemone",
                json=body,
                headers={"Authorization": f"Bearer {self.token()}"},
            )
            response.raise_for_status()
            payload = response.json()
            answer = payload["answers"][QUESTION]
            probabilities = {option: float(answer["probabilities"][option]) for option in OPTIONS}
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as error:
            raise DecisionError(f"decision API: {error}") from error
        return Classification(
            probabilities, payload.get("model", self.deployment), payload.get("usage", {}).get("input_tokens", 0)
        )


@dataclass(frozen=True)
class Decision:
    """`pending` (not classified yet) or the status of a classified candidate."""

    status: str  # pending | confirmed | to_review | dismissed
    classification: Classification | None = None

    @property
    def label(self) -> str | None:
        return self.classification.top[0] if self.classification else None


def status_of(classification: Classification, confidence: float) -> str:
    """Confirmed: real event or sensor fault at or above the confidence; dismissed: expected pattern at or above it;
    otherwise to review (unsure, or cannot tell)."""
    label, probability = classification.top
    if probability >= confidence and label in ANOMALOUS:
        return "confirmed"
    if probability >= confidence and label == "expected_pattern":
        return "dismissed"
    return "to_review"


def decide(classifier: Classifier, state: dict[str, Any], confidence: float) -> Decision:
    with tracer.start_as_current_span("sdp.anomaly.classify") as span:
        span.set_attribute("sdp.anomaly.device_id", state["device"]["id"])
        span.set_attribute("sdp.anomaly.metric", state["metric"]["name"])
        try:
            classification = classifier.classify(state)
        except DecisionError as error:
            log.warning("Classification failed, candidate left pending: %s", error)
            span.record_exception(error)
            span.set_attribute("sdp.anomaly.decision", "pending")
            return Decision("pending")
        status = status_of(classification, confidence)
        for option, probability in classification.probabilities.items():
            span.set_attribute(f"sdp.anomaly.p.{option}", round(probability, 4))
        span.set_attribute("sdp.anomaly.decision", status)
        span.set_attribute("gen_ai.usage.input_tokens", classification.input_tokens)
        return Decision(status, classification)


def _r(value: float) -> float:
    return round(value, 3)


def build_state(
    candidate: Candidate,
    device: Device,
    unit: str,
    neighbours: Iterable[Assessment],
    open_anomalies: Iterable[dict[str, Any]] = (),
) -> dict[str, Any]:
    """The compact document the decision model (and later the LLM) sees: only data, no instructions."""
    a = candidate.assessment
    return {
        "device": {
            "id": device.device_id,
            "name": device.display_name,
            "site": device.site_id,
            "payload_format": device.payload_format,
        },
        "metric": {"name": a.metric, "unit": unit},
        "kind": candidate.kind,
        "window": {
            "from": a.started_at.isoformat(),
            "to": a.ended_at.isoformat(),
            "count": a.count,
            "min": _r(a.observed_min),
            "max": _r(a.observed_max),
            "mean": _r(a.observed_mean),
            "last": _r(a.observed_last),
            "largest_step": _r(a.max_step),
        },
        "usual": {
            "value": _r(a.usual_value),
            "spread": _r(a.spread),
            "range_within_the_hour": [_r(a.usual_min), _r(a.usual_max)] if a.usual_min is not None else None,
        },
        "score": round(candidate.score, 2),
        "same_metric_on_other_devices_of_site": [
            {"device": n.device_id, "score": round(n.score, 2)}
            for n in neighbours
            if n.metric == a.metric and n.device_id != a.device_id
        ],
        "open_anomalies_of_device": list(open_anomalies),
    }
