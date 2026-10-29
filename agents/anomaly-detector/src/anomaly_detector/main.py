"""Job entry point: one detection cycle, then exit (0 = cycle completed, 1 = cycle failed; the next run retries)."""

import logging

from azure.identity import DefaultAzureCredential

from . import db, telemetry
from .config import Settings

log = logging.getLogger("anomaly_detector")

# Locally, credentials come from developer tools (Azure CLI): allow more than the default 10 s on a busy machine.
DEVELOPER_TOOL_TIMEOUT = 60


def run() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logging.getLogger("azure").setLevel(logging.WARNING)
    settings = Settings()
    credential = DefaultAzureCredential(process_timeout=DEVELOPER_TOOL_TIMEOUT)
    telemetry.configure(settings, credential)
    try:
        with (
            telemetry.tracer.start_as_current_span("sdp.anomaly.cycle") as span,
            db.connect(settings, credential) as conn,
        ):
            devices = db.analysed_devices(conn, settings.sites)
            span.set_attribute("sdp.anomaly.sites", settings.sites)
            span.set_attribute("sdp.anomaly.devices", len(devices))
            log.info("Cycle completed: sites=%s devices=%d", ",".join(settings.sites), len(devices))
        return 0
    except Exception:
        log.exception("Cycle failed")
        return 1
    finally:
        telemetry.flush()
