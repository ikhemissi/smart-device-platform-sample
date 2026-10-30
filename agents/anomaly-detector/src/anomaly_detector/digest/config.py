"""Digest job settings (names match the job definition in infra/modules/apps)."""

from functools import cached_property

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_METRICS = "temperature,humidity,pressure,co2,illuminance,battery_voltage,battery_level"


def _csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


class DigestSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    db_host: str = Field(validation_alias="SDP_DB_HOST")
    db_name: str = Field(default="sdp", validation_alias="SDP_DB_NAME")
    db_user: str = Field(validation_alias="SDP_DB_USER")
    openai_endpoint: str = Field(validation_alias="AZURE_OPENAI_ENDPOINT")
    model_deployment: str = Field(default="gpt-6-luna", validation_alias="FOUNDRY_MODEL_DEPLOYMENT_NAME")
    # Empty: every site.
    sites_csv: str = Field(default="", validation_alias="SDP_DIGEST_SITES")
    metrics_csv: str = Field(default=DEFAULT_METRICS, validation_alias="SDP_DIGEST_METRICS")
    applicationinsights_connection_string: str | None = Field(
        default=None, validation_alias="APPLICATIONINSIGHTS_CONNECTION_STRING"
    )

    @cached_property
    def sites(self) -> list[str]:
        return _csv(self.sites_csv)

    @cached_property
    def metrics(self) -> list[str]:
        return _csv(self.metrics_csv) or _csv(DEFAULT_METRICS)
