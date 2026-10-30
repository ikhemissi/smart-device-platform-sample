"""PostgreSQL source and store of the maintenance job (role sdp_insights, migration V10), and the dry-run store that
reads the queue but writes nothing."""

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

from . import data
from .lifecycle import QueueItem
from .models import Probability, WorkOrder
from .run import DeviceInput
from .signals import battery_series, device_forecast, signals


@dataclass
class PgSource:
    conn: psycopg.Connection
    configured_sites: list[str]

    def inputs(self, now: datetime) -> list[DeviceInput]:
        sites = data.sites(self.conn, self.configured_sites)
        if not sites:
            return []
        devices = data.devices(self.conn, sites)
        series = battery_series(data.battery_points(self.conn, sites, now))
        periods = data.message_periods(self.conn, sites, now)
        rejected = data.rejected(self.conn, [d.device_id for d in devices], now)
        return [
            DeviceInput(d, device_forecast(series.get(d.device_id), now), signals(d, periods, rejected, now))
            for d in devices
        ]


@dataclass
class PgStore:
    conn: psycopg.Connection

    def queue(self) -> dict[str, QueueItem]:
        rows = self.conn.execute(
            """SELECT id, device_id, status, work_order_at FROM sdp.maintenance_items
                WHERE status IN ('open', 'planned')"""
        ).fetchall()
        return {r["device_id"]: QueueItem(**r) for r in rows}

    def done_since(self, since: datetime) -> dict[str, datetime]:
        rows = self.conn.execute(
            """SELECT device_id, max(status_at) AS at FROM sdp.maintenance_items
                WHERE status = 'done' AND status_at >= %s GROUP BY device_id""",
            (since,),
        ).fetchall()
        return {r["device_id"]: r["at"] for r in rows}

    def save_assessment(self, entry: DeviceInput, probability: Probability, now: datetime) -> None:
        self.conn.execute(
            """INSERT INTO sdp.device_maintenance (device_id, site_id, assessed_at, battery, signals, probability,
                                                   decision_model, decision_model_version)
               VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
               ON CONFLICT (device_id) DO UPDATE SET site_id = excluded.site_id, assessed_at = excluded.assessed_at,
                   battery = excluded.battery, signals = excluded.signals, probability = excluded.probability,
                   decision_model = excluded.decision_model,
                   decision_model_version = excluded.decision_model_version""",
            (
                entry.device.device_id,
                entry.device.site_id,
                now,
                Jsonb(entry.battery.as_json()) if entry.battery else None,
                Jsonb(entry.signals.as_json()),
                probability.value,
                probability.model,
                probability.version,
            ),
        )

    def open_item(
        self,
        entry: DeviceInput,
        probability: float,
        reasons: list[dict[str, Any]],
        work_order: WorkOrder | None,
        now: datetime,
    ) -> int:
        return self.conn.execute(
            """INSERT INTO sdp.maintenance_items (device_id, site_id, status, probability, reasons, battery, work_order,
                                                  work_order_model, work_order_at, opened_at, updated_at)
               VALUES (%s, %s, 'open', %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id""",
            (
                entry.device.device_id,
                entry.device.site_id,
                probability,
                Jsonb(reasons),
                Jsonb(entry.battery.as_json()) if entry.battery else None,
                work_order.text if work_order else None,
                work_order.model if work_order else None,
                now if work_order else None,
                now,
                now,
            ),
        ).fetchone()["id"]

    def update_item(
        self,
        item: QueueItem,
        entry: DeviceInput,
        probability: float,
        reasons: list[dict[str, Any]],
        work_order: WorkOrder | None,
        now: datetime,
    ) -> None:
        """The new probability, reasons and battery; the work order only when a new one was written. The status
        condition leaves an item an Editor closed meanwhile untouched."""
        self.conn.execute(
            """UPDATE sdp.maintenance_items
                  SET probability = %s, reasons = %s, battery = %s, updated_at = %s,
                      work_order = coalesce(%s, work_order), work_order_model = coalesce(%s, work_order_model),
                      work_order_at = CASE WHEN %s::text IS NULL THEN work_order_at ELSE %s END
                WHERE id = %s AND status IN ('open', 'planned')""",
            (
                probability,
                Jsonb(reasons),
                Jsonb(entry.battery.as_json()) if entry.battery else None,
                now,
                work_order.text if work_order else None,
                work_order.model if work_order else None,
                work_order.text if work_order else None,
                now,
                item.id,
            ),
        )

    def resolve_item(self, item: QueueItem, now: datetime) -> None:
        self.conn.execute(
            """UPDATE sdp.maintenance_items SET status = 'resolved', resolved_at = %s, status_at = %s, updated_at = %s
                WHERE id = %s AND status IN ('open', 'planned')""",
            (now, now, now, item.id),
        )


@dataclass
class DryRunStore:
    """Reads the queue from the database and records the writes instead of making them."""

    reader: PgStore
    writes: list[dict[str, Any]] = field(default_factory=list)

    def queue(self) -> dict[str, QueueItem]:
        return self.reader.queue()

    def done_since(self, since: datetime) -> dict[str, datetime]:
        return self.reader.done_since(since)

    def save_assessment(self, entry: DeviceInput, probability: Probability, now: datetime) -> None:
        self.writes.append(
            {
                "assessment": entry.device.device_id,
                "battery": entry.battery.as_json() if entry.battery else None,
                "signals": entry.signals.as_json(),
                "probability": round(probability.value, 3),
            }
        )

    def open_item(self, entry, probability, reasons, work_order, now) -> int:
        return 0

    def update_item(self, item, entry, probability, reasons, work_order, now) -> None:
        return None

    def resolve_item(self, item, now) -> None:
        return None
