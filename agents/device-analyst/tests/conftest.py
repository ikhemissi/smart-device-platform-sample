"""Test doubles: signed Entra-like tokens, Redis (fakeredis), the platform API (respx) and a scripted chat client
that drives the real Agent Framework tool loop."""

import json
import time
import uuid
from collections.abc import AsyncIterator, Sequence
from typing import Any

import fakeredis
import httpx
import jwt
import pytest
from agent_framework import BaseChatClient, ChatResponse, ChatResponseUpdate, Content, Message
from agent_framework._middleware import ChatMiddlewareLayer
from agent_framework._tools import FunctionInvocationLayer
from agent_framework.observability import ChatTelemetryLayer
from cryptography.hazmat.primitives.asymmetric import rsa

from device_analyst.analyst import Analyst
from device_analyst.app import create_app, no_lifespan
from device_analyst.auth import TokenValidator
from device_analyst.conversations import ConversationStore
from device_analyst.platform_api import PlatformApi
from device_analyst.tools import build_tools

TENANT = "11111111-1111-1111-1111-111111111111"
AUDIENCE = "api://sdp-test"
API = "https://apim.test/api/v1"
ALICE = "aaaaaaaa-0000-0000-0000-000000000001"
BOB = "bbbbbbbb-0000-0000-0000-000000000002"

_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def token(oid: str = ALICE, roles: Sequence[str] = ("DeviceData.Read",), **overrides: Any) -> str:
    now = int(time.time())
    claims = {
        "iss": f"https://login.microsoftonline.com/{TENANT}/v2.0",
        "aud": AUDIENCE,
        "oid": oid,
        "name": "Test User",
        "roles": list(roles),
        "iat": now,
        "nbf": now,
        "exp": now + 3600,
    } | overrides
    return jwt.encode({k: v for k, v in claims.items() if v is not None}, _KEY, algorithm="RS256")


def auth(oid: str = ALICE, roles: Sequence[str] = ("DeviceData.Read",)) -> dict[str, str]:
    return {"Authorization": f"Bearer {token(oid, roles)}"}


class ScriptedChatClient(FunctionInvocationLayer, ChatMiddlewareLayer, ChatTelemetryLayer, BaseChatClient):
    """Plays one scripted model response per call. A step is ("tool", name, arguments) or ("text", answer); the text
    is streamed in small deltas. Composed with the same layers as OpenAIChatClient."""

    def __init__(self, steps: list[tuple[Any, ...]]):
        super().__init__(function_invocation_configuration={"max_function_calls": 10})
        self.steps = list(steps)
        self.calls: list[list[Message]] = []
        self.options: list[dict] = []

    def _inner_get_response(self, *, messages, stream, options, **kwargs):
        self.calls.append(list(messages))
        self.options.append(dict(options))
        step = self.steps.pop(0) if self.steps else ("text", "(no more script)")
        if step[0] == "tool":
            contents = [
                Content.from_function_call(f"call-{uuid.uuid4().hex[:8]}", step[1], arguments=json.dumps(step[2]))
            ]
        else:
            contents = [Content.from_text(step[1])]

        if not stream:

            async def response() -> ChatResponse:
                return ChatResponse(messages=[Message("assistant", contents)])

            return response()

        async def updates() -> AsyncIterator[ChatResponseUpdate]:
            if step[0] == "text":
                words = step[1].split(" ")
                for i, word in enumerate(words):
                    yield ChatResponseUpdate(
                        role="assistant", contents=[Content.from_text(word + (" " if i < len(words) - 1 else ""))]
                    )
            else:
                yield ChatResponseUpdate(role="assistant", contents=contents)

        return self._build_response_stream(updates())


@pytest.fixture
def redis() -> fakeredis.FakeAsyncRedis:
    return fakeredis.FakeAsyncRedis()


@pytest.fixture
def store(redis: fakeredis.FakeAsyncRedis) -> ConversationStore:
    return ConversationStore(redis, ttl_seconds=3600, max_messages=50)


@pytest.fixture
def api_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(base_url=API)


@pytest.fixture
def platform_api(api_client: httpx.AsyncClient) -> PlatformApi:
    return PlatformApi(api_client)


def make_app(store: ConversationStore, analyst: Analyst | None = None, platform_api: PlatformApi | None = None):
    analyst = analyst or Analyst(
        ScriptedChatClient([("text", "Hello")]), build_tools(platform_api) if platform_api else []
    )
    validator = TokenValidator(TENANT, [AUDIENCE, "other-audience"], key_resolver=lambda _: _KEY.public_key())
    return create_app(validator=validator, store=store, analyst=analyst, lifespan=no_lifespan)


def client_for(app) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://agent.test")


def parse_sse(body: str) -> list[tuple[str, dict[str, Any]]]:
    events: list[tuple[str, dict[str, Any]]] = []
    for block in body.replace("\r\n", "\n").split("\n\n"):
        name, data = None, []
        for line in block.split("\n"):
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].strip())
        if name:
            events.append((name, json.loads("\n".join(data)) if data else {}))
    return events
