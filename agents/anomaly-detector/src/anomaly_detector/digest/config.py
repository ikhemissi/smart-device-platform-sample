"""Digest job settings (names match the job definition in infra/modules/apps)."""

from functools import cached_property

from pydantic import Field

from ..config import JobSettings, split_csv

# Batteries are left out: the maintenance queue in the same digest covers them (refinements design D2).
DEFAULT_METRICS = "temperature,humidity,pressure,co2,illuminance"


class DigestSettings(JobSettings):
    # Empty: every site.
    sites_csv: str = Field(default="", validation_alias="SDP_DIGEST_SITES")
    metrics_csv: str = Field(default=DEFAULT_METRICS, validation_alias="SDP_DIGEST_METRICS")
    # Predictive maintenance (M9) runs: the figures of the sites it assesses include their maintenance queue.
    maintenance_enabled: bool = Field(default=False, validation_alias="SDP_MAINTENANCE_ENABLED")
    # Empty: every site (like the maintenance job).
    maintenance_sites_csv: str = Field(default="", validation_alias="SDP_MAINTENANCE_SITES")

    @cached_property
    def sites(self) -> list[str]:
        return split_csv(self.sites_csv)

    @cached_property
    def metrics(self) -> list[str]:
        return split_csv(self.metrics_csv) or split_csv(DEFAULT_METRICS)

    def assesses(self, site_id: str) -> bool:
        """Whether the maintenance job assesses the site (its queue is part of the figures)."""
        sites = split_csv(self.maintenance_sites_csv)
        return self.maintenance_enabled and (not sites or site_id in sites)
