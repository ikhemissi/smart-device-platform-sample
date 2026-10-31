"""OpenTelemetry (M7 design D10): Azure Monitor export with the workload identity, httpx instrumentation for the
model calls. Without APPLICATIONINSIGHTS_CONNECTION_STRING (local runs) spans are not exported."""

import logging
import os
from collections.abc import Iterator
from contextlib import contextmanager

from azure.core.credentials import TokenCredential
from opentelemetry import trace
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.trace import Span, SpanKind, Status, StatusCode

from .config import JobSettings

log = logging.getLogger(__name__)
tracer = trace.get_tracer("anomaly_detector")


def configure(settings: JobSettings, credential: TokenCredential) -> None:
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
    instrumentor = HTTPXClientInstrumentor()
    if not instrumentor.is_instrumented_by_opentelemetry:  # the distro may have instrumented httpx already
        instrumentor.instrument()


def flush() -> None:
    """The process exits after one cycle: export the buffered spans first."""
    provider = trace.get_tracer_provider()
    if hasattr(provider, "force_flush"):
        provider.force_flush()


@contextmanager
def job_span(name: str) -> Iterator[Span]:
    """A job run as one Application Insights request (SERVER span). Run the whole job inside it, connection included,
    so that a database or model failure at any step shows as a failed request."""
    with tracer.start_as_current_span(name, kind=SpanKind.SERVER) as span:
        try:
            yield span
        except Exception as error:
            span.record_exception(error)
            span.set_status(Status(StatusCode.ERROR, type(error).__name__))
            raise
