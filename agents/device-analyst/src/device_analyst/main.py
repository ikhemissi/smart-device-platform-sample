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
# Locally, credentials come from developer tools (Azure CLI): allow more than the default 10 s on a busy machine.
# In Azure, the workload identity (AZURE_CLIENT_ID) is used and this does not apply.
DEVELOPER_TOOL_TIMEOUT = 60
telemetry.configure(settings)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    credential = DefaultAzureCredential(process_timeout=DEVELOPER_TOOL_TIMEOUT)
    redis = Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        ssl=True,
        decode_responses=False,
        credential_provider=create_from_default_azure_credential(
            ("https://redis.azure.com/.default",), app_kwargs={"process_timeout": DEVELOPER_TOOL_TIMEOUT}
        ),
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
    tools = build_tools(PlatformApi(http), anomalies=settings.anomalies_enabled, digests=settings.digests_enabled)
    app.state.analyst = Analyst(client, tools, reasoning_effort=settings.reasoning_effort)
    try:
        # Warm up before the first readiness probe: the Redis connection and the model's Entra token (getting the
        # first token takes seconds, which would delay the first answer).
        await app.state.store.ping()
        await credential.get_token("https://cognitiveservices.azure.com/.default")
    except Exception as e:  # noqa: BLE001 - readiness reports it
        logging.getLogger(__name__).warning("Warm-up failed: %r", e)
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
