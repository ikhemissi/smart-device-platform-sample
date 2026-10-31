"""Model calls of the maintenance job: the intervention probability from Microsoft-Decision-1 (M9 design D4) and the
work order from the chat model (design D6). Both see the computed figures only."""

import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx
import openai

from ..chat import chat_client, respond, set_request_attributes, set_usage_attributes
from ..decision import DecisionError, input_tokens, post_question
from ..digest.brief import unsupported_numbers
from ..telemetry import tracer
from .battery import Forecast
from .signals import MaintenanceDevice, Signals

log = logging.getLogger(__name__)

QUESTION = "intervention"
INSTRUCTIONS = (
    "Will this device need an on-site intervention (battery replacement, repair or reset) within the next 7 days?"
)
# Explicit criteria (live check, M9 task 2.5): with general wording, the model ignored a missing battery (p ~ 0.9 for
# devices without one) and rated failing communication low (silent 6 h: 0.08; gaps 20x usual: 0.09).
CRITERIA = {
    "true": "the battery forecast reaches its cut-off within 7 days; or the device's communication is failing: silent "
    "for more than an hour, gaps between messages over the last 24 hours more than ten times its usual interval, or "
    "20 or more rejected messages in 24 hours",
    "false": "otherwise; a device that does not report its battery is judged on its communication only",
}
MODEL_VERSION = "1"


@dataclass(frozen=True)
class Probability:
    value: float
    model: str
    version: str = MODEL_VERSION
    input_tokens: int = 0


class Assesses(Protocol):
    def probability(self, state: dict[str, Any]) -> Probability: ...


def build_state(device: MaintenanceDevice, battery: Forecast | None, signals: Signals) -> dict[str, Any]:
    """The document the decision model sees: the device, its battery forecast and its signals; no instructions."""
    return {
        "device": {"id": device.device_id, "site": device.site_id, "payload_format": device.payload_format},
        "battery": battery.as_json() if battery else {"reported": False},
        "signals": signals.as_json(),
    }


@dataclass
class InterventionClient:
    endpoint: str
    deployment: str
    token: Callable[[], str]
    client: httpx.Client = field(default_factory=lambda: httpx.Client(timeout=30))

    def probability(self, state: dict[str, Any]) -> Probability:
        body = {
            "model": self.deployment,
            "state": state,
            "questions": {QUESTION: {"type": "noul", "instructions": INSTRUCTIONS, "criteria": CRITERIA}},
        }
        try:
            payload = post_question(self.client, self.endpoint, self.token, body)
            value = float(payload["answers"][QUESTION]["noul"])
            if not 0.0 <= value <= 1.0:
                raise ValueError(f"probability out of range: {value}")
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as error:
            raise DecisionError(f"decision API: {error}") from error
        return Probability(value, payload.get("model", self.deployment), input_tokens=input_tokens(payload))


def assess(client: Assesses, state: dict[str, Any]) -> Probability | None:
    """None when the decision model fails: the device keeps its previous assessment and the next run retries."""
    with tracer.start_as_current_span("sdp.maintenance.decide") as span:
        span.set_attribute("sdp.maintenance.device_id", state["device"]["id"])
        try:
            result = client.probability(state)
        except DecisionError as error:
            log.warning("Assessment of %s failed, previous one kept: %s", state["device"]["id"], error)
            span.record_exception(error)
            span.set_attribute("sdp.maintenance.decision", "failed")
            return None
        span.set_attribute("sdp.maintenance.probability", round(result.value, 4))
        span.set_attribute("gen_ai.request.model", result.model)
        span.set_attribute("gen_ai.usage.input_tokens", result.input_tokens)
        return result


WORK_ORDER_INSTRUCTIONS = """You write the work order of an on-site intervention on an IoT device: a short instruction a
field technician can act on. Write two to four short sentences, in English, plain text (no lists, no line breaks, no
markdown).
Start with the action: replace the battery, inspect the device, or check its connectivity, naming the device id (and
its name when there is one) and its site.
Then give the facts behind the visit in plain words, the way a colleague would say them: the battery level and the days
left before it runs out, how long the device has been silent, how irregular its reporting is, or how many of its
messages were rejected.
End with the urgency in words (for example: before the battery runs out, at the next visit, as soon as possible).
Do not mention rules, rule names, thresholds, probabilities or reason numbers.
Use numbers exactly as they appear in the input. Use only facts present in the input; do not guess causes."""


@dataclass(frozen=True)
class WorkOrder:
    text: str
    model: str


class WritesWorkOrders(Protocol):
    def write(self, data: dict[str, Any]) -> WorkOrder | None: ...


@dataclass
class WorkOrderWriter:
    openai_endpoint: str
    deployment: str
    token: Callable[[], str]
    reasoning_effort: str = "low"
    http_client: httpx.Client | None = None
    client: openai.OpenAI = field(init=False)

    def __post_init__(self) -> None:
        self.client = chat_client(self.openai_endpoint, self.token, timeout=60, http_client=self.http_client)

    def write(self, data: dict[str, Any]) -> WorkOrder | None:
        """`data`: device, battery, signals, reasons and probability. None when the model fails: the item stays without
        work order and a later run retries."""
        device_id = data["device"]["id"]
        with tracer.start_as_current_span("sdp.maintenance.work_order") as span:
            span.set_attribute("sdp.maintenance.device_id", device_id)
            set_request_attributes(span, self.deployment)
            try:
                response = respond(
                    self.client,
                    self.deployment,
                    WORK_ORDER_INSTRUCTIONS,
                    data,
                    self.reasoning_effort,
                    max_output_tokens=1500,
                )
            except openai.OpenAIError as error:
                log.warning("Work order failed for %s, item left without one: %s", device_id, error)
                span.record_exception(error)
                return None
            set_usage_attributes(span, response.usage)
            text = response.output_text.strip()
            if not text:
                log.warning("Work order failed for %s: empty answer (status %s)", device_id, response.status)
                return None
            unsupported = unsupported_numbers(text, data)
            span.set_attribute("sdp.maintenance.unsupported_numbers", len(unsupported))
            if unsupported:
                log.warning("Work order of %s states numbers not in its input: %s", device_id, unsupported)
            return WorkOrder(text, self.deployment)
