"""assistant-monitoring tasks 1.1 and 1.2: per-turn span and tool error kinds, on an in-memory span exporter."""

import asyncio

import pytest
import respx
from agent_framework import ChatResponseUpdate, Content
from agent_framework.observability import enable_instrumentation
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import StatusCode

from device_analyst.analyst import Analyst
from device_analyst.app import create_app, no_lifespan, pseudonym
from device_analyst.auth import TokenValidator
from device_analyst.tools import build_tools

from .conftest import _KEY, ALICE, API, AUDIENCE, TENANT, ScriptedChatClient, auth, client_for, parse_sse

EXPORTER = InMemorySpanExporter()
_PROVIDER = TracerProvider()
_PROVIDER.add_span_processor(SimpleSpanProcessor(EXPORTER))
trace.set_tracer_provider(_PROVIDER)
enable_instrumentation()


@pytest.fixture(autouse=True)
def clear():
    EXPORTER.clear()


def app_with(store, model, platform_api=None, timeout=110):
    validator = TokenValidator(TENANT, [AUDIENCE], key_resolver=lambda _: _KEY.public_key())
    analyst = Analyst(model, build_tools(platform_api) if platform_api else [])
    return create_app(
        validator=validator, store=store, analyst=analyst, turn_timeout_seconds=timeout, lifespan=no_lifespan
    )


async def ask(app, content="question", context=None):
    async with client_for(app) as client:
        conversation = (await client.post("/api/v1/agent/conversations", headers=auth())).json()["id"]
        body = {"content": content, **({"context": context} if context else {})}
        response = await client.post(f"/api/v1/agent/conversations/{conversation}/messages", headers=auth(), json=body)
        return parse_sse(response.text)


def spans(name):
    return [s for s in EXPORTER.get_finished_spans() if s.name.startswith(name)]


@respx.mock
async def test_answered_turn_with_a_tool_call(store, platform_api):
    respx.get(f"{API}/devices/does-not-exist").respond(404, json={"detail": "Device 'does-not-exist' does not exist."})
    respx.get(f"{API}/devices").respond(json={"items": [], "total": 0})
    model = ScriptedChatClient(
        [("tool", "get_device", {"device_id": "does-not-exist"}), ("text", "I cannot find this device.")]
    )

    events = await ask(app_with(store, model, platform_api), context={"deviceId": "lyon-01-bthome-004"})

    assert events[-1][0] == "done"
    [turn] = spans("sdp.agent.turn")
    assert turn.attributes["sdp.agent.outcome"] == "answered"
    assert turn.attributes["sdp.agent.tool_calls"] == 1
    assert turn.attributes["sdp.agent.first_text_ms"] >= 0
    assert turn.attributes["sdp.agent.device_context"] is True
    assert turn.attributes["enduser.pseudo.id"] == pseudonym(ALICE) != ALICE
    [tool] = spans("execute_tool")
    assert tool.attributes["sdp.agent.tool.error"] == "not_found"
    assert tool.context.trace_id == turn.context.trace_id, "tool calls belong to the turn's trace"
    assert spans("invoke_agent")[0].parent.span_id == turn.context.span_id


async def test_failed_turn(store):
    class Failing(ScriptedChatClient):
        def _inner_get_response(self, **kwargs):
            raise RuntimeError("model down")

    events = await ask(app_with(store, Failing([])))

    assert events[-1][0] == "error"
    [turn] = spans("sdp.agent.turn")
    assert turn.attributes["sdp.agent.outcome"] == "failed"
    assert "sdp.agent.first_text_ms" not in turn.attributes
    assert turn.status.status_code == StatusCode.ERROR


async def test_timed_out_turn(store):
    class Slow(ScriptedChatClient):
        def _inner_get_response(self, **kwargs):
            async def slow():
                await asyncio.sleep(5)
                yield ChatResponseUpdate(role="assistant", contents=[Content.from_text("too late")])

            return self._build_response_stream(slow())

    events = await ask(app_with(store, Slow([]), timeout=0.2))

    assert events[-1] == ("error", {"title": "Timeout", "detail": "The answer took too long. Try a narrower question."})
    [turn] = spans("sdp.agent.turn")
    assert turn.attributes["sdp.agent.outcome"] == "timeout"
    assert turn.attributes["sdp.agent.device_context"] is False
    assert turn.status.status_code == StatusCode.ERROR
