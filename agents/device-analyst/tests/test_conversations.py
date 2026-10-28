"""Task 3.4: private, expiring, bounded conversations (fakeredis)."""

from agent_framework import Message

from device_analyst.analyst import Analyst
from device_analyst.conversations import KEY_PREFIX, ConversationStore, Turn

from .conftest import ALICE, BOB, ScriptedChatClient, auth, client_for, make_app, parse_sse


def turn(question: str, answer: str = "answer", messages: list[dict] | None = None) -> Turn:
    return Turn(question=question, answer=answer, asked_at="2026-10-28T12:00:00Z", messages=messages or [])


async def test_another_users_conversation_is_404(store):
    """Scenario: user B requests user A's conversation id."""
    app = make_app(store)
    async with client_for(app) as client:
        conversation = (await client.post("/api/v1/agent/conversations", headers=auth(ALICE))).json()["id"]
        for request in (
            client.get(f"/api/v1/agent/conversations/{conversation}", headers=auth(BOB)),
            client.delete(f"/api/v1/agent/conversations/{conversation}", headers=auth(BOB)),
            client.post(
                f"/api/v1/agent/conversations/{conversation}/messages", headers=auth(BOB), json={"content": "hi"}
            ),
        ):
            assert (await request).status_code == 404
        assert (await client.get(f"/api/v1/agent/conversations/{conversation}", headers=auth(ALICE))).status_code == 200
        assert (await client.get("/api/v1/agent/conversations/not-a-uuid", headers=auth(ALICE))).status_code == 404


async def test_follow_up_question_keeps_the_context(store):
    """Scenario: after asking about paris-01-ruuvi5-001 the user asks "and its humidity?"."""
    model = ScriptedChatClient([("text", "paris-01-ruuvi5-001 is at 21.4 °C."), ("text", "Its humidity is 48 %.")])
    app = make_app(store, Analyst(model, []))
    async with client_for(app) as client:
        conversation = (await client.post("/api/v1/agent/conversations", headers=auth())).json()["id"]
        for question in ("What is the temperature of paris-01-ruuvi5-001?", "and its humidity?"):
            response = await client.post(
                f"/api/v1/agent/conversations/{conversation}/messages", headers=auth(), json={"content": question}
            )
            assert parse_sse(response.text)[-1][0] == "done"

    second_call = [(m.role, m.text) for m in model.calls[1] if m.role != "system"]
    assert second_call == [
        ("user", "What is the temperature of paris-01-ruuvi5-001?"),
        ("assistant", "paris-01-ruuvi5-001 is at 21.4 °C."),
        ("user", "and its humidity?"),
    ]


async def test_conversations_expire_an_hour_after_the_last_message(store, redis):
    conversation = await store.create(ALICE)
    assert 3590 < await redis.ttl(KEY_PREFIX + conversation.id) <= 3600

    await redis.expire(KEY_PREFIX + conversation.id, 10)
    await store.append(conversation, turn("again?"))
    assert await redis.ttl(KEY_PREFIX + conversation.id) > 3590, "a new message restarts the hour"

    await redis.delete(KEY_PREFIX + conversation.id)
    assert await store.get(conversation.id, ALICE) is None


async def test_at_most_fifty_messages_are_kept(redis):
    store = ConversationStore(redis, max_messages=50)
    conversation = await store.create(ALICE)
    for i in range(30):
        await store.append(conversation, turn(f"question {i}"))

    stored = await store.get(conversation.id, ALICE)
    assert len(stored.transcript()) == 50
    assert stored.transcript()[0]["content"] == "question 5"


async def test_tool_messages_are_kept_for_the_latest_turns_only(store):
    conversation = await store.create(ALICE)
    tool_messages = [
        Message("user", ["q"]).to_dict(),
        {
            "type": "message",
            "role": "tool",
            "contents": [{"type": "function_result", "call_id": "c1", "result": "<data>{}</data>"}],
        },
        Message("assistant", ["a"]).to_dict(),
    ]
    for i in range(5):
        await store.append(conversation, turn(f"q{i}", f"a{i}", tool_messages))

    roles = [m.role for m in (await store.get(conversation.id, ALICE)).history()]
    assert roles == ["user", "assistant", "user", "assistant"] + ["user", "tool", "assistant"] * 3


async def test_delete_and_concurrent_messages(store):
    conversation = await store.create(ALICE)
    await store.lock(conversation.id)
    app = make_app(store)
    async with client_for(app) as client:
        busy = await client.post(
            f"/api/v1/agent/conversations/{conversation.id}/messages", headers=auth(), json={"content": "hi"}
        )
        assert busy.status_code == 409
        assert (
            await client.delete(f"/api/v1/agent/conversations/{conversation.id}", headers=auth())
        ).status_code == 204
        assert (await client.get(f"/api/v1/agent/conversations/{conversation.id}", headers=auth())).status_code == 404
