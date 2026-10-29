"""Explanations by the chat model (M7 design D4): for confirmed anomalies only, from the decision's data only."""

import json
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import httpx
import openai

from .decision import Classification
from .telemetry import tracer

log = logging.getLogger(__name__)

INSTRUCTIONS = """You explain an anomaly detected on an IoT sensor to a facility operator.
Write at most four short sentences, in English, as plain text.
Name the device id, the metric, the observed values and the usual value with their unit (rounded to one decimal),
and the period (UTC times as HH:MM).
Say what the classification means (a real change in the environment, or a sensor or device misbehaving) and mention
whether the other devices of the site show the same change.
Use only facts present in the data; do not guess causes that the data does not support and do not give advice."""

LABELS = {
    "real_event": "real event in the environment",
    "sensor_fault": "sensor or device fault",
    "expected_pattern": "expected pattern",
    "cannot_tell": "cannot tell",
}


@dataclass(frozen=True)
class Explanation:
    text: str
    model: str


@dataclass
class Explainer:
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

    def explain(self, state: dict[str, Any], classification: Classification) -> Explanation | None:
        """None when the model fails: the anomaly stays confirmed without explanation and the next cycle retries."""
        label, probability = classification.top
        data = {"anomaly": state, "classification": LABELS[label], "probability": round(probability, 3)}
        with tracer.start_as_current_span("sdp.anomaly.explain") as span:
            span.set_attribute("gen_ai.operation.name", "responses")
            span.set_attribute("gen_ai.request.model", self.deployment)
            try:
                response = self.client.responses.create(
                    model=self.deployment,
                    instructions=INSTRUCTIONS,
                    input=json.dumps(data, ensure_ascii=False),
                    reasoning={"effort": self.reasoning_effort},
                    max_output_tokens=1500,
                    store=False,
                )
            except openai.OpenAIError as error:
                log.warning("Explanation failed, anomaly left without explanation: %s", error)
                span.record_exception(error)
                return None
            if response.usage:
                span.set_attribute("gen_ai.usage.input_tokens", response.usage.input_tokens)
                span.set_attribute("gen_ai.usage.output_tokens", response.usage.output_tokens)
            text = response.output_text.strip()
            if not text:
                log.warning("Explanation failed: empty answer (status %s)", response.status)
                return None
            return Explanation(text, self.deployment)
