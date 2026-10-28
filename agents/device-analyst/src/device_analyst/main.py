"""Service entry point: wires settings, telemetry, Entra credentials, Redis, the platform API client and the agent."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
import uvicorn
from agent_framework.openai import OpenAIChatClient
from azure.identity.aio import DefaultAzureCredential
from fastapi import FastAPI
from redis.asyncio import Redis
from redis_entraid.cred_provider import create_from_default_azure_credential

from . import telemetry
from .analyst import Analyst
from .app import create_app
from .auth import TokenValidator
from .config import Settings
from .conversations import ConversationStore
from .platform_api import PlatformApi
from .tools import build_tools

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
settings = Settings()
telemetry.configure(settings)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    credential = DefaultAzureCredential()
    redis = Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        ssl=True,
        decode_responses=False,
        credential_provider=create_from_default_azure_credential(("https://redis.azure.com/.default",)),
        socket_timeout=5,
        health_check_interval=30,
    )
    http = httpx.AsyncClient(base_url=settings.api_base_url.rstrip("/"), timeout=httpx.Timeout(20, connect=5))
    client = OpenAIChatClient(
        model=settings.model_deployment,
        azure_endpoint=settings.openai_endpoint,
        credential=credential,
        function_invocation_configuration={"max_function_calls": settings.max_tool_calls},
    )
    app.state.token_validator = TokenValidator(settings.tenant_id, settings.api_audiences)
    app.state.store = ConversationStore(redis, settings.conversation_ttl_seconds, settings.max_conversation_messages)
    app.state.analyst = Analyst(client, build_tools(PlatformApi(http)))
    try:
        # Warm up the Redis connection (first Entra token) before the first readiness probe.
        await app.state.store.ping()
    except Exception as e:  # noqa: BLE001 - readiness reports it
        logging.getLogger(__name__).warning("Redis is not reachable yet: %r", e)
    try:
        yield
    finally:
        await http.aclose()
        await redis.aclose()
        await credential.close()


app = create_app(
    lifespan=lifespan,
    turn_timeout_seconds=settings.turn_timeout_seconds,
    messages_per_minute=settings.messages_per_minute,
)


def main() -> None:
    uvicorn.run(app, host="0.0.0.0", port=8080, proxy_headers=True, forwarded_allow_ips="*", log_config=None)


if __name__ == "__main__":
    main()
