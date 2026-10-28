"""Conversation API of the device analyst (spec device-analyst-agent), streamed with Server-Sent Events."""

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from sse_starlette import EventSourceResponse

from .analyst import Analyst, TurnResult
from .auth import TokenValidator, User, reader
from .conversations import ConversationBusyError, ConversationStore, Turn, now_iso
from .platform_api import caller_token
from .problems import ProblemError, not_found, problem_response

log = logging.getLogger(__name__)
PREFIX = "/api/v1/agent"
CurrentUser = Annotated[User, Depends(reader)]


class MessageContext(BaseModel):
    deviceId: str | None = Field(default=None, max_length=200)  # noqa: N815 - JSON field name


class MessageRequest(BaseModel):
    content: str = Field(min_length=1, max_length=2000)
    context: MessageContext | None = None


def create_app(
    *,
    validator: TokenValidator | None = None,
    store: ConversationStore | None = None,
    analyst: Analyst | None = None,
    turn_timeout_seconds: float = 110,
    lifespan=None,
) -> FastAPI:
    """The API. Dependencies are given by tests, or created by `lifespan` (see main.py) for the real service."""
    app = FastAPI(
        title="Smart Device Platform — device analyst", version="1", lifespan=lifespan, docs_url=None, redoc_url=None
    )
    if validator is not None:
        app.state.token_validator = validator
    if store is not None:
        app.state.store = store
    if analyst is not None:
        app.state.analyst = analyst
    app.state.turn_timeout_seconds = turn_timeout_seconds

    @app.exception_handler(ProblemError)
    async def problem(request: Request, error: ProblemError) -> JSONResponse:
        return problem_response(request, error)

    @app.exception_handler(RequestValidationError)
    async def invalid(request: Request, error: RequestValidationError) -> JSONResponse:
        errors: dict[str, list[str]] = {}
        for e in error.errors():
            errors.setdefault(".".join(str(p) for p in e["loc"][1:]) or "body", []).append(e["msg"])
        response = problem_response(request, ProblemError(400, "Bad Request", "The request is invalid."))
        body = json.loads(response.body)
        body["errors"] = errors
        return JSONResponse(body, status_code=400, media_type="application/problem+json")

    @app.get("/health/live", include_in_schema=False)
    async def live() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/health/ready", include_in_schema=False)
    async def ready(request: Request) -> Response:
        try:
            await asyncio.wait_for(request.app.state.store.ping(), 10)
            return JSONResponse({"status": "UP"})
        except Exception as e:  # noqa: BLE001 - any failure means not ready
            log.warning("Readiness check failed: %r", e)
            return JSONResponse({"status": "DOWN"}, status_code=503)

    @app.post(PREFIX + "/conversations", status_code=201)
    async def create(request: Request, user: CurrentUser) -> dict[str, Any]:
        conversation = await request.app.state.store.create(user.object_id)
        return {"id": conversation.id, "createdAt": conversation.created_at, "messages": []}

    @app.get(PREFIX + "/conversations/{conversation_id}")
    async def read(request: Request, conversation_id: str, user: CurrentUser) -> dict[str, Any]:
        conversation = await request.app.state.store.get(conversation_id, user.object_id)
        if conversation is None:
            raise not_found(f"Conversation '{conversation_id}' does not exist or has expired.")
        return {"id": conversation.id, "createdAt": conversation.created_at, "messages": conversation.transcript()}

    @app.delete(PREFIX + "/conversations/{conversation_id}", status_code=204)
    async def delete(request: Request, conversation_id: str, user: CurrentUser) -> Response:
        if not await request.app.state.store.delete(conversation_id, user.object_id):
            raise not_found(f"Conversation '{conversation_id}' does not exist or has expired.")
        return Response(status_code=204)

    @app.post(PREFIX + "/conversations/{conversation_id}/messages")
    async def message(
        request: Request, conversation_id: str, body: MessageRequest, user: CurrentUser
    ) -> EventSourceResponse:
        store: ConversationStore = request.app.state.store
        conversation = await store.get(conversation_id, user.object_id)
        if conversation is None:
            raise not_found(f"Conversation '{conversation_id}' does not exist or has expired.")
        try:
            await store.lock(conversation_id)
        except ConversationBusyError as e:
            raise ProblemError(
                409, "Conflict", "The previous message of this conversation is still being answered."
            ) from e
        device_id = body.context.deviceId if body.context else None
        asked_at = now_iso()

        async def events() -> AsyncIterator[dict[str, str]]:
            token = caller_token.set(user.token)
            try:
                result: TurnResult | None = None
                async with asyncio.timeout(request.app.state.turn_timeout_seconds):
                    async for event in request.app.state.analyst.run(conversation.history(), body.content, device_id):
                        if event.result is not None:
                            result = event.result
                            await store.append(
                                conversation,
                                Turn(
                                    question=body.content,
                                    answer=result.text,
                                    asked_at=asked_at,
                                    citations=result.citations,
                                    device_id=device_id,
                                    messages=[m.to_dict() for m in result.messages],
                                ),
                            )
                            yield sse("done", {"conversationId": conversation_id, **event.data})
                        else:
                            yield sse(event.type, event.data)
            except TimeoutError:
                yield sse("error", {"title": "Timeout", "detail": "The answer took too long. Try a narrower question."})
            except Exception:
                log.exception("Agent turn failed")
                yield sse("error", {"title": "Error", "detail": "The assistant could not answer. Try again later."})
            finally:
                caller_token.reset(token)
                await store.unlock(conversation_id)

        # X-Accel-Buffering: proxies must not buffer the stream.
        return EventSourceResponse(events(), ping=15, headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    return app


def sse(event: str, data: dict[str, Any]) -> dict[str, str]:
    return {"event": event, "data": json.dumps(data, ensure_ascii=False)}


@asynccontextmanager
async def no_lifespan(_: FastAPI) -> AsyncIterator[None]:
    yield
