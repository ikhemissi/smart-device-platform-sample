"""Maintenance job entry point. Default: one run (assess the devices of the configured sites, keep the queue), then exit
(0 = run completed, model failures included: they are traced and retried by later runs; 1 = run failed).
`--dry-run [--site <id>]`: print the assessments and the queue changes, without writing anything; `--no-models` skips
the model calls and prints the forecasts, signals and the reasons they would give."""

import argparse
import json
import logging
from datetime import UTC, datetime
from typing import Any

import psycopg

from .. import db, jobs, telemetry
from .config import MaintenanceSettings
from .lifecycle import reasons
from .models import Assesses, InterventionClient, WorkOrderWriter, WritesWorkOrders
from .run import MaintenanceRun, RunResult, traced_run
from .store import DryRunStore, PgSource, PgStore

log = logging.getLogger("maintenance")


def _parse(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="maintenance", description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="print assessments and queue changes; write nothing")
    parser.add_argument("--site", help="dry run: only this site")
    parser.add_argument("--no-models", action="store_true", help="dry run: no model call (forecasts and signals)")
    args = parser.parse_args(argv)
    if (args.site or args.no_models) and not args.dry_run:
        parser.error("--site and --no-models need --dry-run")
    return args


def _print(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2, default=str))


def run(argv: list[str] | None = None) -> int:
    args = _parse(argv)
    jobs.configure_logging()
    settings = MaintenanceSettings()
    credential = jobs.credential()
    token = jobs.model_token(credential)
    assessor = InterventionClient(settings.decision_endpoint, settings.decision_deployment, token)
    writer = WorkOrderWriter(settings.openai_endpoint, settings.model_deployment, token)
    now = datetime.now(UTC)
    sites = [args.site] if args.site else settings.sites

    if args.dry_run:
        with db.connect(settings, credential) as conn:
            source = PgSource(conn, sites)
            if args.no_models:
                _print(_inputs(source, now))
                return 0
            output = _dry_run(conn, source, assessor, writer, settings.confidence, now)
        _print(output)
        return 0

    telemetry.configure(settings, credential)

    def execute() -> RunResult:
        with db.connect(settings, credential) as conn:
            return MaintenanceRun(PgSource(conn, sites), PgStore(conn), assessor, writer, settings.confidence).run(now)

    try:
        r = traced_run(execute)
        log.info(
            "Maintenance run completed: devices=%d assessed=%d opened=%d updated=%d resolved=%d work_orders=%d "
            "errors=%d",
            r.devices,
            r.assessed,
            r.opened,
            r.updated,
            r.resolved,
            r.work_orders,
            r.errors,
        )
        return 0
    except Exception:
        log.exception("Maintenance run failed")
        return 1
    finally:
        telemetry.flush()


def _inputs(source: PgSource, now: datetime) -> list[dict[str, Any]]:
    """`--no-models`: the forecasts, the signals and the reasons each device would be queued with."""
    return [
        {
            "device": e.device.device_id,
            "battery": e.battery_json(),
            "signals": e.signals.as_json(),
            "reasons_if_queued": reasons(e.battery, e.signals, 1.0),
        }
        for e in source.inputs(now)
    ]


def _dry_run(
    conn: psycopg.Connection,
    source: PgSource,
    assessor: Assesses,
    writer: WritesWorkOrders,
    confidence: float,
    now: datetime,
) -> dict[str, Any]:
    """A run with the models that writes nothing: the assessments it would save and the queue changes it would make."""
    store = DryRunStore(PgStore(conn))
    result = MaintenanceRun(source, store, assessor, writer, confidence).run(now)
    counts = {k: v for k, v in vars(result).items() if k != "changes"}
    return {"assessments": store.writes, "queue_changes": result.changes, "counts": counts}
