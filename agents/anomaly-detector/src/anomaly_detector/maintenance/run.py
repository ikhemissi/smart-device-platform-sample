"""One maintenance run (M9 design D4, D5): assess every device of the configured sites and keep the queue. The same
logic runs against PostgreSQL and, in tests and dry runs, against fakes."""

import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Protocol

from opentelemetry.trace import SpanKind, Status, StatusCode

from ..telemetry import tracer
from .battery import Forecast
from .lifecycle import DONE_BLOCKS, QueueItem, change, reasons
from .models import Assesses, Probability, WorkOrder, WritesWorkOrders, assess, build_state
from .signals import MaintenanceDevice, Signals

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class DeviceInput:
    device: MaintenanceDevice
    battery: Forecast | None
    signals: Signals


class Source(Protocol):
    def inputs(self, now: datetime) -> list[DeviceInput]: ...


class Store(Protocol):
    def queue(self) -> dict[str, QueueItem]: ...
    def done_since(self, since: datetime) -> dict[str, datetime]: ...
    def save_assessment(self, entry: DeviceInput, probability: Probability, now: datetime) -> None: ...
    def open_item(
        self, entry: DeviceInput, probability: float, reasons: list[dict[str, Any]], work_order: WorkOrder | None,
        now: datetime,
    ) -> int: ...  # fmt: skip
    def update_item(
        self, item: QueueItem, entry: DeviceInput, probability: float, reasons: list[dict[str, Any]],
        work_order: WorkOrder | None, now: datetime,
    ) -> None: ...  # fmt: skip
    def resolve_item(self, item: QueueItem, now: datetime) -> None: ...


@dataclass
class RunResult:
    devices: int = 0
    assessed: int = 0
    opened: int = 0
    updated: int = 0
    resolved: int = 0
    work_orders: int = 0
    errors: int = 0
    changes: list[dict[str, Any]] = field(default_factory=list)


def work_order_input(entry: DeviceInput, probability: float, found: list[dict[str, Any]]) -> dict[str, Any]:
    device = entry.device
    return {
        "device": {"id": device.device_id, "site": device.site_id, "name": device.display_name},
        "battery": entry.battery.as_json() if entry.battery else None,
        "signals": entry.signals.as_json(),
        "reasons": found,
        "probability": round(probability, 3),
    }


@dataclass
class MaintenanceRun:
    source: Source
    store: Store
    assessor: Assesses
    writer: WritesWorkOrders
    confidence: float

    def run(self, now: datetime) -> RunResult:
        result = RunResult()
        inputs = self.source.inputs(now)
        result.devices = len(inputs)
        queue = self.store.queue()
        done = self.store.done_since(now - DONE_BLOCKS)
        for entry in inputs:
            device_id = entry.device.device_id
            probability = assess(self.assessor, build_state(entry.device, entry.battery, entry.signals))
            if probability is None:
                result.errors += 1
                continue
            self.store.save_assessment(entry, probability, now)
            result.assessed += 1
            p = probability.value
            decided = change(p, self.confidence, queue.get(device_id), done.get(device_id), now)
            if decided.action in ("none", "blocked"):
                continue
            if decided.action == "resolve":
                self.store.resolve_item(decided.item, now)
                result.resolved += 1
                result.changes.append({"action": "resolve", "device": device_id, "probability": round(p, 3)})
                continue
            found = reasons(entry.battery, entry.signals, p)
            order = None
            if decided.write_work_order:
                order = self.writer.write(work_order_input(entry, p, found))
                if order is None:
                    result.errors += 1
                else:
                    result.work_orders += 1
            if decided.action == "open":
                self.store.open_item(entry, p, found, order, now)
                result.opened += 1
            else:
                self.store.update_item(decided.item, entry, p, found, order, now)
                result.updated += 1
            result.changes.append({"action": decided.action, "device": device_id, "probability": round(p, 3),
                                   "reasons": found, "work_order": order.text if order else None})  # fmt: skip
        return result


COUNTS = ("devices", "assessed", "opened", "updated", "resolved", "work_orders", "errors")


def traced_run(execute: Callable[[], RunResult]) -> RunResult:
    """A run as one Application Insights request (SERVER span) with its counts. `execute` connects and runs, so a
    database failure shows as a failed request too."""
    with tracer.start_as_current_span("sdp.maintenance.run", kind=SpanKind.SERVER) as span:
        try:
            result = execute()
        except Exception as error:
            span.record_exception(error)
            span.set_status(Status(StatusCode.ERROR, type(error).__name__))
            raise
        for name in COUNTS:
            span.set_attribute(f"sdp.maintenance.{name}", getattr(result, name))
        if result.errors:
            span.set_status(Status(StatusCode.ERROR, f"{result.errors} model call(s) failed"))
        return result
