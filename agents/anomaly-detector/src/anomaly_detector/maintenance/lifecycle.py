"""Queue lifecycle (M9 design D5), pure: the reasons of an assessment and what it changes in the queue."""

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from .battery import Forecast
from .signals import Signals

DAYS_LEFT = 7.0
SILENT_MINUTES = 15.0
GAP_RATIO = 5.0
REJECTED = 5
WORK_ORDER_REFRESH = timedelta(hours=24)
DONE_BLOCKS = timedelta(days=7)


@dataclass(frozen=True)
class QueueItem:
    id: int
    device_id: str
    status: str  # open | planned
    work_order_at: datetime | None


def reasons(battery: Forecast | None, signals: Signals, probability: float) -> list[dict[str, Any]]:
    """The computed figures that cross a rule, with their values; when none does, the battery forecast and the
    probability."""
    found: list[dict[str, Any]] = []
    if battery is not None and battery.days_left is not None and battery.days_left <= DAYS_LEFT:
        found.append({"rule": "battery_days_left", "value": battery.days_left, "threshold": DAYS_LEFT, "unit": "days",
                      "level": battery.level, "level_unit": battery.unit})  # fmt: skip
    if signals.minutes_since_last is not None and signals.minutes_since_last > SILENT_MINUTES:
        found.append({"rule": "silent", "value": round(signals.minutes_since_last, 1), "threshold": SILENT_MINUTES,
                      "unit": "minutes"})  # fmt: skip
    ratio = signals.gap_ratio
    if ratio is not None and ratio >= GAP_RATIO:
        found.append({"rule": "reporting_gaps", "value": round(ratio, 1), "threshold": GAP_RATIO,
                      "unit": "times the usual interval", "longest_gap_s": round(signals.last_24h.longest_gap_s, 1),
                      "usual_interval_s": round(signals.previous_7d.median_interval_s, 1)})  # fmt: skip
    if signals.rejected_24h >= REJECTED:
        found.append({"rule": "rejected_messages", "value": signals.rejected_24h, "threshold": REJECTED,
                      "unit": "messages in 24 hours"})  # fmt: skip
    if found:
        return found
    fallback: list[dict[str, Any]] = []
    if battery is not None:
        fallback.append({"rule": "battery_forecast", "value": battery.level, "unit": battery.unit,
                         "days_left": battery.days_left, "slope_per_day": battery.slope_per_day})  # fmt: skip
    fallback.append({"rule": "probability", "value": round(probability, 3)})
    return fallback


@dataclass(frozen=True)
class Change:
    action: str  # open | update | resolve | none | blocked
    item: QueueItem | None = None
    write_work_order: bool = False


def change(
    probability: float,
    confidence: float,
    queued: QueueItem | None,
    last_done_at: datetime | None,
    now: datetime,
) -> Change:
    if probability >= confidence:
        if queued is not None:
            stale = queued.work_order_at is None or now - queued.work_order_at >= WORK_ORDER_REFRESH
            return Change("update", queued, stale)
        if last_done_at is not None and now - last_done_at < DONE_BLOCKS:
            return Change("blocked")
        return Change("open", None, True)
    if queued is not None:
        return Change("resolve", queued)
    return Change("none")
