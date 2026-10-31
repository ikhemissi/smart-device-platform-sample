"""The chat model through the Responses API, shared by the anomaly explanation, the digest brief and the work order:
the client, the request and the GenAI attributes of their spans."""

import json
from collections.abc import Callable
from typing import Any

import httpx
import openai
from openai.types.responses import Response, ResponseUsage
from opentelemetry.trace import Span


def chat_client(
    endpoint: str, token: Callable[[], str], timeout: float, http_client: httpx.Client | None
) -> openai.OpenAI:
    return openai.OpenAI(
        base_url=f"{endpoint.rstrip('/')}/openai/v1/",
        api_key=token,
        timeout=timeout,
        max_retries=1,
        http_client=http_client,
    )


def respond(
    client: openai.OpenAI,
    deployment: str,
    instructions: str,
    data: dict[str, Any],
    reasoning_effort: str,
    max_output_tokens: int,
) -> Response:
    """The model's answer to `data` (sent as JSON), not stored by the service."""
    return client.responses.create(
        model=deployment,
        instructions=instructions,
        input=json.dumps(data, ensure_ascii=False),
        reasoning={"effort": reasoning_effort},
        max_output_tokens=max_output_tokens,
        store=False,
    )


def set_request_attributes(span: Span, deployment: str) -> None:
    span.set_attribute("gen_ai.operation.name", "responses")
    span.set_attribute("gen_ai.request.model", deployment)


def set_usage_attributes(span: Span, usage: ResponseUsage | None) -> None:
    if usage:
        span.set_attribute("gen_ai.usage.input_tokens", usage.input_tokens)
        span.set_attribute("gen_ai.usage.output_tokens", usage.output_tokens)
