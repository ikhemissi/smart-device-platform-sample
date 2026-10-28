"""Settings from the environment (names match the container app definition in infra/modules/apps)."""

from functools import cached_property

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    tenant_id: str = Field(validation_alias="SDP_ENTRA_TENANT_ID")
    api_audiences_csv: str = Field(validation_alias="SDP_API_AUDIENCES")
    api_base_url: str = Field(validation_alias="SDP_API_BASE_URL")
    openai_endpoint: str = Field(validation_alias="AZURE_OPENAI_ENDPOINT")
    model_deployment: str = Field(
        default="gpt-6-luna",
        validation_alias=AliasChoices("FOUNDRY_MODEL_DEPLOYMENT_NAME", "AGENT_MODEL_DEPLOYMENT_NAME"),
    )
    redis_host: str | None = Field(default=None, validation_alias="SDP_REDIS_HOST")
    redis_port: int = Field(default=10000, validation_alias="SDP_REDIS_PORT")
    applicationinsights_connection_string: str | None = Field(
        default=None, validation_alias="APPLICATIONINSIGHTS_CONNECTION_STRING"
    )
    # Prompts, answers and tool results in traces: off by default, enabled per environment for demos (design D7).
    capture_content: bool = Field(default=False, validation_alias="SDP_AGENT_CAPTURE_CONTENT")
    conversation_ttl_seconds: int = Field(default=3600, validation_alias="SDP_AGENT_CONVERSATION_TTL_SECONDS")
    max_conversation_messages: int = Field(default=50, validation_alias="SDP_AGENT_MAX_MESSAGES")
    messages_per_minute: int = Field(default=20, validation_alias="SDP_AGENT_MESSAGES_PER_MINUTE")
    # gpt-6-luna reasons before answering; "low" keeps the first text within 5 s for multi-tool questions.
    reasoning_effort: str = Field(default="low", validation_alias="SDP_AGENT_REASONING_EFFORT")
    max_tool_calls: int = Field(default=10, validation_alias="SDP_AGENT_MAX_TOOL_CALLS")
    turn_timeout_seconds: float = Field(default=110, validation_alias="SDP_AGENT_TURN_TIMEOUT_SECONDS")

    @cached_property
    def api_audiences(self) -> list[str]:
        return [a.strip() for a in self.api_audiences_csv.split(",") if a.strip()]
