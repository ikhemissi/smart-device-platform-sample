"""Task 3.5: a full SSE turn through the API, with the real agent loop, tools and a scripted model."""

import respx

from device_analyst.analyst import Analyst
from device_analyst.tools import build_tools

from .conftest import ALICE, API, ScriptedChatClient, auth, client_for, make_app, parse_sse

PARIS = [
    {
        "siteId": "paris-01",
        "displayName": "Paris",
        "devices": 10,
        "reporting": 9,
        "silent": 1,
        "silentDevices": [{"deviceId": "paris-01-lpp-003", "lastSeenAt": "2026-10-28T10:00:00Z"}],
    }
]


async def create(client) -> str:
    response = await client.post("/api/v1/agent/conversations", headers=auth())
    assert response.status_code == 201
    return response.json()["id"]


@respx.mock
async def test_streaming_answer_with_tool_and_citations(store, platform_api):
    """Scenario: streaming answer to "How many devices are reporting at site paris-01?"."""
    route = respx.get(f"{API}/fleet/status").respond(json=PARIS)
    model = ScriptedChatClient(
        [("tool", "fleet_status", {"site_id": "paris-01"}), ("text", "9 of the 10 devices of paris-01 are reporting.")]
    )
    app = make_app(store, Analyst(model, build_tools(platform_api)))

    async with client_for(app) as client:
        conversation = await create(client)
        response = await client.post(
            f"/api/v1/agent/conversations/{conversation}/messages",
            headers=auth(),
            json={"content": "How many devices are reporting at site paris-01?"},
        )
        events = parse_sse(response.text)

        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        assert events[0][0] == "tool"
        assert events[0][1] == {"name": "fleet_status", "arguments": {"site_id": "paris-01"}}
        assert "".join(d["text"] for e, d in events if e == "delta") == "9 of the 10 devices of paris-01 are reporting."
        assert events[-1] == (
            "done",
            {"conversationId": conversation, "citations": [{"kind": "site", "siteId": "paris-01"}]},
        )
        assert route.calls.last.request.headers["authorization"] == auth()["Authorization"]

        transcript = (await client.get(f"/api/v1/agent/conversations/{conversation}", headers=auth())).json()
        assert [m["role"] for m in transcript["messages"]] == ["user", "assistant"]
        assert transcript["messages"][1]["citations"] == [{"kind": "site", "siteId": "paris-01"}]


async def test_device_context_reaches_the_model(store):
    model = ScriptedChatClient([("text", "The battery is fine.")])
    app = make_app(store, Analyst(model, []))

    async with client_for(app) as client:
        conversation = await create(client)
        await client.post(
            f"/api/v1/agent/conversations/{conversation}/messages",
            headers=auth(),
            json={"content": "Is the battery OK?", "context": {"deviceId": "lyon-01-bthome-004"}},
        )

    system = [m.text for m in model.calls[0] if m.role == "system"]
    assert any("lyon-01-bthome-004" in s for s in system)
    transcript = (await store.get(conversation, ALICE)).transcript()
    assert transcript[0]["context"] == {"deviceId": "lyon-01-bthome-004"}


async def test_model_failure_ends_the_stream_with_an_error_event(store):
    class Failing(ScriptedChatClient):
        def _inner_get_response(self, **kwargs):
            raise RuntimeError("model down")

    app = make_app(store, Analyst(Failing([]), []))
    async with client_for(app) as client:
        conversation = await create(client)
        response = await client.post(
            f"/api/v1/agent/conversations/{conversation}/messages", headers=auth(), json={"content": "hi"}
        )
        assert parse_sse(response.text)[-1][0] == "error"
        # Nothing stored and the conversation is not left locked.
        assert (await store.get(conversation, ALICE)).turns == []
        retry = await client.post(
            f"/api/v1/agent/conversations/{conversation}/messages", headers=auth(), json={"content": "hi"}
        )
        assert retry.status_code == 200


async def test_invalid_messages_are_400(store):
    app = make_app(store)
    async with client_for(app) as client:
        conversation = await create(client)
        for body in ({"content": ""}, {"content": "x" * 2001}, {}):
            response = await client.post(
                f"/api/v1/agent/conversations/{conversation}/messages", headers=auth(), json=body
            )
            assert response.status_code == 400, body
            assert "content" in response.json()["errors"]
