"""Conversations in Azure Managed Redis (M6 design D4): one JSON document per conversation under
`sdp:agent:conv:{id}`, bound to its owner (Entra object id), expiring after an hour without a new message and holding
at most 50 messages (oldest turns are dropped). Tool calls and results are kept for the latest turns only, so follow-up
questions keep their context while the model input stays bounded."""

import json
import uuid
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from typing import Any

from agent_framework import Message
from redis.asyncio import Redis

KEY_PREFIX = "sdp:agent:conv:"
LOCK_SECONDS = 150
TURNS_WITH_TOOLS = 3


@dataclass
class Turn:
    question: str
    answer: str
    asked_at: str
    citations: list[dict[str, Any]] = field(default_factory=list)
    device_id: str | None = None
    # Agent messages of the turn (user question, tool calls and results, answer) as Agent Framework dicts.
    messages: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class Conversation:
    id: str
    owner: str
    created_at: str
    turns: list[Turn] = field(default_factory=list)

    def history(self) -> list[Message]:
        """Model context: full messages of the latest turns, question and answer only for older ones."""
        messages: list[Message] = []
        for index, t in enumerate(self.turns):
            if index >= len(self.turns) - TURNS_WITH_TOOLS and t.messages:
                messages.extend(Message.from_dict(m) for m in t.messages)
            else:
                messages.append(Message("user", [t.question]))
                messages.append(Message("assistant", [t.answer]))
        return messages

    def transcript(self) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for t in self.turns:
            question: dict[str, Any] = {"role": "user", "content": t.question, "at": t.asked_at}
            if t.device_id:
                question["context"] = {"deviceId": t.device_id}
            result.append(question)
            result.append({"role": "assistant", "content": t.answer, "citations": t.citations})
        return result

    def to_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def from_json(raw: str | bytes) -> "Conversation":
        data = json.loads(raw)
        return Conversation(
            id=data["id"],
            owner=data["owner"],
            created_at=data["created_at"],
            turns=[Turn(**t) for t in data.get("turns", [])],
        )


class ConversationBusyError(Exception):
    """Another message of the conversation is being answered."""


class ConversationStore:
    def __init__(self, redis: Redis, ttl_seconds: int = 3600, max_messages: int = 50):
        self._redis = redis
        self._ttl = ttl_seconds
        self._max_turns = max(1, max_messages // 2)

    async def create(self, owner: str) -> Conversation:
        conversation = Conversation(id=str(uuid.uuid4()), owner=owner, created_at=_now())
        await self._save(conversation)
        return conversation

    async def get(self, conversation_id: str, owner: str) -> Conversation | None:
        """The conversation, or None when it does not exist, has expired or belongs to someone else."""
        if not _is_id(conversation_id):
            return None
        raw = await self._redis.get(KEY_PREFIX + conversation_id)
        if raw is None:
            return None
        conversation = Conversation.from_json(raw)
        return conversation if conversation.owner == owner else None

    async def append(self, conversation: Conversation, turn: Turn) -> None:
        conversation.turns.append(turn)
        del conversation.turns[: max(0, len(conversation.turns) - self._max_turns)]
        await self._save(conversation)

    async def delete(self, conversation_id: str, owner: str) -> bool:
        if await self.get(conversation_id, owner) is None:
            return False
        await self._redis.delete(KEY_PREFIX + conversation_id, KEY_PREFIX + conversation_id + ":lock")
        return True

    async def lock(self, conversation_id: str) -> None:
        if not await self._redis.set(KEY_PREFIX + conversation_id + ":lock", "1", nx=True, ex=LOCK_SECONDS):
            raise ConversationBusyError(conversation_id)

    async def unlock(self, conversation_id: str) -> None:
        await self._redis.delete(KEY_PREFIX + conversation_id + ":lock")

    async def ping(self) -> bool:
        return bool(await self._redis.ping())

    async def _save(self, conversation: Conversation) -> None:
        # Sliding expiry: every new message restarts the hour.
        await self._redis.set(KEY_PREFIX + conversation.id, conversation.to_json(), ex=self._ttl)


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _is_id(value: str) -> bool:
    try:
        return str(uuid.UUID(value)) == value
    except ValueError:
        return False


def now_iso() -> str:
    return _now()
