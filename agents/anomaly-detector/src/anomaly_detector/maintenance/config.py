"""Maintenance job settings (names match the job definition in infra/modules/apps)."""

from functools import cached_property

from pydantic import Field

from ..config import JobSettings, split_csv


class MaintenanceSettings(JobSettings):
    decision_endpoint: str = Field(validation_alias="SDP_DECISION_ENDPOINT")
    decision_deployment: str = Field(default="decision-1", validation_alias="SDP_DECISION_DEPLOYMENT")
    # Empty: every site.
    sites_csv: str = Field(default="", validation_alias="SDP_MAINTENANCE_SITES")
    confidence: float = Field(default=0.4, gt=0, le=1, validation_alias="SDP_MAINTENANCE_CONFIDENCE")

    @cached_property
    def sites(self) -> list[str]:
        return split_csv(self.sites_csv)
