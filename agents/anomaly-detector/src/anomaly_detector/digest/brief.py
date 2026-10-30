"""The digest's brief (M8 design D4): a few sentences written by the language model from the figures only, and a check
that every number it states can be found in those figures."""

import json
import logging
import re
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

import httpx
import openai

from ..telemetry import tracer

log = logging.getLogger(__name__)

INSTRUCTIONS = """You write the daily brief of an IoT site for its managers, from the figures you are given.
Write one paragraph of at most six short sentences, in English, plain text (no lists, no line breaks, no markdown).
Start with how many devices are reporting and name any silent device with the time it was last seen.
Then mention notable metric changes compared with the previous 7 days (a change of 10 % or more), the alerts, the
anomalies, rejected messages and certificates expiring soon. Summarise anomalies: how many, by classification in plain
words (real event, sensor fault, expected pattern, cannot tell) and by status (confirmed, to review, reviewed), naming
at most three devices with the confidence of their most recent anomaly.
When maintenance figures are present, give the number of devices in the maintenance queue (open, and planned visits)
and name at most three of the most likely ones with their probability of needing a visit within 7 days and, when
known, their battery days left; if the queue is empty, say that no device needs a visit.
Use numbers exactly as they appear in the figures (with their unit), name devices by their id, and give times in UTC as
HH:MM. If nothing is notable, say so in one sentence. Use only facts present in the figures; do not guess causes or
give advice."""

# Numbers a brief may state without them being figures: the periods' lengths (and the maintenance horizon of 7 days)
# and the silence and certificate windows.
DEFINITIONS = {24.0, 7.0, 15.0, 30.0}
# A number not glued to letters or to an identifier's hyphen (so "ruuvi3", "paris-01" and "CO2" are not numbers).
NUMBER = re.compile(r"(?<![\w.\-])-?\d+(?:\.\d+)?(?![\w])")


@dataclass(frozen=True)
class Brief:
    text: str
    model: str
    input_tokens: int = 0
    output_tokens: int = 0


@dataclass
class Briefer:
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
            timeout=90,
            max_retries=1,
            http_client=self.http_client,
        )

    def write(self, figures: dict[str, Any]) -> Brief | None:
        """None when the model fails: the digest keeps its figures and a later run adds the brief."""
        with tracer.start_as_current_span("sdp.digest.brief") as span:
            span.set_attribute("sdp.digest.site", figures["site"]["id"])
            span.set_attribute("gen_ai.operation.name", "responses")
            span.set_attribute("gen_ai.request.model", self.deployment)
            try:
                response = self.client.responses.create(
                    model=self.deployment,
                    instructions=INSTRUCTIONS,
                    input=json.dumps(figures, ensure_ascii=False),
                    reasoning={"effort": self.reasoning_effort},
                    max_output_tokens=2000,
                    store=False,
                )
            except openai.OpenAIError as error:
                log.warning("Brief failed for %s, digest kept without brief: %s", figures["site"]["id"], error)
                span.record_exception(error)
                return None
            text = response.output_text.strip()
            usage = response.usage
            if usage:
                span.set_attribute("gen_ai.usage.input_tokens", usage.input_tokens)
                span.set_attribute("gen_ai.usage.output_tokens", usage.output_tokens)
            if not text:
                log.warning("Brief failed for %s: empty answer (status %s)", figures["site"]["id"], response.status)
                return None
            unsupported = unsupported_numbers(text, figures)
            span.set_attribute("sdp.digest.unsupported_numbers", len(unsupported))
            if unsupported:
                log.warning("Brief of %s states numbers not in its figures: %s", figures["site"]["id"], unsupported)
            return Brief(text, self.deployment, usage.input_tokens if usage else 0, usage.output_tokens if usage else 0)


def _values(node: Any) -> Iterator[float]:
    if isinstance(node, bool) or node is None:
        return
    if isinstance(node, int | float):
        yield float(node)
        yield abs(float(node))
    elif isinstance(node, str):
        try:
            at = datetime.fromisoformat(node)
        except ValueError:
            return
        yield from (float(at.year), float(at.month), float(at.day), float(at.hour), float(at.minute))
    elif isinstance(node, dict):
        for value in node.values():
            yield from _values(value)
    elif isinstance(node, list):
        yield float(len(node))
        for value in node:
            yield from _values(value)


def unsupported_numbers(text: str, figures: dict[str, Any]) -> list[str]:
    """Numbers of the brief that match no figure, even rounded to the precision the brief uses."""
    known = set(_values(figures)) | DEFINITIONS
    missing = []
    for match in NUMBER.finditer(text):
        token = match.group()
        number = abs(float(token))
        decimals = len(token.split(".")[1]) if "." in token else 0
        if not any(round(value, decimals) == number for value in known):
            missing.append(token)
    return missing
