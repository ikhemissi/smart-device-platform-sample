"""OpenTelemetry (M7 design D10): Azure Monitor export with the workload identity, httpx instrumentation for the
model calls. Without APPLICATIONINSIGHTS_CONNECTION_STRING (local runs) spans are not exported."""

import logging
import os

from azure.core.credentials import TokenCredential
from opentelemetry import trace
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

from .config import Settings

log = logging.getLogger(__name__)
tracer = trace.get_tracer("anomaly_detector")


def configure(settings: Settings, credential: TokenCredential) -> None:
    os.environ.setdefault("OTEL_SERVICE_NAME", "sdp-anomaly-detector")
    # One short burst of spans per run: keep them all (the distro's default sampler is rate-limited).
    os.environ.setdefault("OTEL_TRACES_SAMPLER", "microsoft.fixed_percentage")
    os.environ.setdefault("OTEL_TRACES_SAMPLER_ARG", "1.0")
    if settings.applicationinsights_connection_string:
        from azure.monitor.opentelemetry import configure_azure_monitor

        configure_azure_monitor(
            connection_string=settings.applicationinsights_connection_string,
            credential=credential,
            logger_name="anomaly_detector",
            # Only the spans of this job and its httpx model calls: no Azure SDK or requests spans (token requests).
            instrumentation_options={
                name: {"enabled": False}
                for name in ("azure_sdk", "requests", "urllib", "urllib3", "django", "fastapi", "flask", "psycopg2")
            },
        )
    HTTPXClientInstrumentor().instrument()


def flush() -> None:
    """The process exits after one cycle: export the buffered spans first."""
    provider = trace.get_tracer_provider()
    if hasattr(provider, "force_flush"):
        provider.force_flush()
