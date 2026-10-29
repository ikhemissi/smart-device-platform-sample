"""Settings from the environment (names match the job definition in infra/modules/apps)."""

from functools import cached_property

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


def _csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    db_host: str = Field(validation_alias="SDP_DB_HOST")
    db_name: str = Field(default="sdp", validation_alias="SDP_DB_NAME")
    db_user: str = Field(validation_alias="SDP_DB_USER")
    decision_endpoint: str = Field(validation_alias="SDP_DECISION_ENDPOINT")
    decision_deployment: str = Field(default="decision-1", validation_alias="SDP_DECISION_DEPLOYMENT")
    openai_endpoint: str = Field(validation_alias="AZURE_OPENAI_ENDPOINT")
    model_deployment: str = Field(default="gpt-6-luna", validation_alias="FOUNDRY_MODEL_DEPLOYMENT_NAME")
    sites_csv: str = Field(default="paris-01", validation_alias="SDP_ANOMALY_SITES")
    metrics_csv: str = Field(
        default="temperature,humidity,pressure,co2,illuminance", validation_alias="SDP_ANOMALY_METRICS"
    )
    deviation: float = Field(default=4.0, gt=0, validation_alias="SDP_ANOMALY_DEVIATION")
    confidence: float = Field(default=0.7, gt=0, le=1, validation_alias="SDP_ANOMALY_CONFIDENCE")
    applicationinsights_connection_string: str | None = Field(
        default=None, validation_alias="APPLICATIONINSIGHTS_CONNECTION_STRING"
    )

    @cached_property
    def sites(self) -> list[str]:
        return _csv(self.sites_csv)

    @cached_property
    def metrics(self) -> list[str]:
        return _csv(self.metrics_csv)
