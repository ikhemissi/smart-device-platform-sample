"""Model calls of the maintenance job: the intervention probability from Microsoft-Decision-1 (M9 design D4) and the
work order from the chat model (design D6). Both see the computed figures only."""

import json
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx
import openai

from ..decision import DecisionError
from ..digest.brief import unsupported_numbers
from ..telemetry import tracer
from .battery import Forecast
from .signals import MaintenanceDevice, Signals

log = logging.getLogger(__name__)

QUESTION = "intervention"
INSTRUCTIONS = (
    "Will this device need an on-site intervention (battery replacement, repair or reset) within the next 7 days?"
)
CRITERIA = {
    "true": "the battery reaches its cut-off within 7 days, or the device is failing to report",
    "false": "the device will keep working without a visit for 7 days",
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
        "battery": battery.as_json() if battery else None,
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
            response = self.client.post(
                f"{self.endpoint.rstrip('/')}/providers/microsoft/v1/systemone",
                json=body,
                headers={"Authorization": f"Bearer {self.token()}"},
            )
            response.raise_for_status()
            payload = response.json()
            value = float(payload["answers"][QUESTION]["noul"])
            if not 0.0 <= value <= 1.0:
                raise ValueError(f"probability out of range: {value}")
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as error:
            raise DecisionError(f"decision API: {error}") from error
        return Probability(
            value, payload.get("model", self.deployment), input_tokens=payload.get("usage", {}).get("input_tokens", 0)
        )


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


WORK_ORDER_INSTRUCTIONS = """You write the work order of an on-site intervention on an IoT device for a technician.
Write at most four short sentences, in English, plain text (no lists, no line breaks, no markdown).
Start with the action: replace the battery, inspect the device, or check its connectivity.
Name the device id and its site, give the numbers of the reasons with their units, and the urgency (the days left
before the battery cut-off when there is a forecast).
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
        self.client = openai.OpenAI(
            base_url=f"{self.openai_endpoint.rstrip('/')}/openai/v1/",
            api_key=self.token,
            timeout=60,
            max_retries=1,
            http_client=self.http_client,
        )

    def write(self, data: dict[str, Any]) -> WorkOrder | None:
        """`data`: device, battery, signals, reasons and probability. None when the model fails: the item stays without
        work order and a later run retries."""
        device_id = data["device"]["id"]
        with tracer.start_as_current_span("sdp.maintenance.work_order") as span:
            span.set_attribute("sdp.maintenance.device_id", device_id)
            span.set_attribute("gen_ai.operation.name", "responses")
            span.set_attribute("gen_ai.request.model", self.deployment)
            try:
                response = self.client.responses.create(
                    model=self.deployment,
                    instructions=WORK_ORDER_INSTRUCTIONS,
                    input=json.dumps(data, ensure_ascii=False),
                    reasoning={"effort": self.reasoning_effort},
                    max_output_tokens=1500,
                    store=False,
                )
            except openai.OpenAIError as error:
                log.warning("Work order failed for %s, item left without one: %s", device_id, error)
                span.record_exception(error)
                return None
            if response.usage:
                span.set_attribute("gen_ai.usage.input_tokens", response.usage.input_tokens)
                span.set_attribute("gen_ai.usage.output_tokens", response.usage.output_tokens)
            text = response.output_text.strip()
            if not text:
                log.warning("Work order failed for %s: empty answer (status %s)", device_id, response.status)
                return None
            unsupported = unsupported_numbers(text, data)
            span.set_attribute("sdp.maintenance.unsupported_numbers", len(unsupported))
            if unsupported:
                log.warning("Work order of %s states numbers not in its input: %s", device_id, unsupported)
            return WorkOrder(text, self.deployment)
