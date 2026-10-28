"""OpenTelemetry (M6 design D7): Azure Monitor export with Entra authentication, Agent Framework GenAI
instrumentation (model calls with token usage, tool calls) and httpx propagation to API Management. Content capture
(prompts, answers, tool results) is off unless SDP_AGENT_CAPTURE_CONTENT=true. For local debugging, set
OTEL_EXPORTER_OTLP_ENDPOINT (e.g. http://localhost:4317, the Foundry Toolkit trace viewer) instead."""

import logging
import os

from agent_framework.observability import configure_otel_providers, enable_instrumentation
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

from .config import Settings

log = logging.getLogger(__name__)


def configure(settings: Settings) -> None:
    os.environ.setdefault("OTEL_SERVICE_NAME", "sdp-device-analyst")
    os.environ.setdefault("OTEL_PYTHON_FASTAPI_EXCLUDED_URLS", "health/live,health/ready")
    # Keep every span: the distro's default rate-limited sampler (5 spans/s) drops parts of a single agent turn,
    # which emits a burst of spans (model calls, tools, API calls). Override per environment if traffic grows.
    os.environ.setdefault("OTEL_TRACES_SAMPLER", "microsoft.fixed_percentage")
    os.environ.setdefault("OTEL_TRACES_SAMPLER_ARG", "1.0")
    if settings.applicationinsights_connection_string:
        from azure.identity import DefaultAzureCredential
        from azure.monitor.opentelemetry import configure_azure_monitor

        # Application Insights has local authentication disabled: export with the workload identity.
        configure_azure_monitor(
            connection_string=settings.applicationinsights_connection_string,
            credential=DefaultAzureCredential(),
            logger_name="device_analyst",
        )
        log.info("Telemetry exported to Azure Monitor")
    elif os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT"):
        configure_otel_providers(enable_sensitive_data=settings.capture_content)
        log.info("Telemetry exported to %s", os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"])
    enable_instrumentation(enable_sensitive_data=settings.capture_content)
    HTTPXClientInstrumentor().instrument()
