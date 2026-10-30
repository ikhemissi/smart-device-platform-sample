"""Reporting signals and battery series (M9 design D2, D3), pure computations over the plain rows of `data.py`."""

from collections import defaultdict
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from .battery import LEVEL, METRICS, VOLTAGE, Forecast, HourlyBattery, forecast

RECENT = timedelta(hours=24)
PREVIOUS = timedelta(days=7)


@dataclass(frozen=True)
class MaintenanceDevice:
    device_id: str
    site_id: str
    display_name: str | None
    payload_format: str | None
    last_seen_at: datetime | None


@dataclass(frozen=True)
class Period:
    """Messages of a device over a period: count, median and longest interval between consecutive messages (seconds;
    none with fewer than two messages)."""

    messages: int = 0
    median_interval_s: float | None = None
    longest_gap_s: float | None = None

    def as_json(self) -> dict[str, Any]:
        return {
            "messages": self.messages,
            "median_interval_s": _r(self.median_interval_s),
            "longest_gap_s": _r(self.longest_gap_s),
        }


@dataclass(frozen=True)
class Signals:
    minutes_since_last: float | None
    last_24h: Period
    previous_7d: Period
    rejected_24h: int

    @property
    def gap_ratio(self) -> float | None:
        """Longest gap of the last 24 hours over the usual (median) interval of the 7 days before."""
        usual = self.previous_7d.median_interval_s
        if self.last_24h.longest_gap_s is None or not usual:
            return None
        return self.last_24h.longest_gap_s / usual

    def as_json(self) -> dict[str, Any]:
        ratio = self.gap_ratio
        return {
            "minutes_since_last": _r(self.minutes_since_last),
            "last_24h": self.last_24h.as_json(),
            "previous_7d": self.previous_7d.as_json(),
            "longest_gap_over_usual_interval": round(ratio, 1) if ratio is not None else None,
            "rejected_messages_24h": self.rejected_24h,
        }


def _r(value: float | None) -> float | None:
    return round(value, 1) if value is not None else None


def signals(
    device: MaintenanceDevice,
    periods: Iterable[Mapping[str, Any]],
    rejected: Mapping[str, int],
    now: datetime,
) -> Signals:
    """`periods`: rows of `data.message_periods` (any device; the device's are picked), `rejected`: rejected messages
    of the last 24 hours by device."""
    recent, previous = Period(), Period()
    for row in periods:
        if row["device_id"] != device.device_id:
            continue
        period = Period(row["messages"], row["median_interval_s"], row["longest_gap_s"])
        if row["recent"]:
            recent = period
        else:
            previous = period
    since = max(0.0, (now - device.last_seen_at) / timedelta(minutes=1)) if device.last_seen_at else None
    return Signals(since, recent, previous, rejected.get(device.device_id, 0))


def battery_series(rows: Iterable[Mapping[str, Any]]) -> dict[str, dict[str, list[HourlyBattery]]]:
    """Rows of `data.battery_points` by device, then by battery metric, in time order."""
    series: dict[str, dict[str, list[HourlyBattery]]] = defaultdict(lambda: defaultdict(list))
    for row in rows:
        if row["metric"] in METRICS and row["average"] is not None:
            series[row["device_id"]][row["metric"]].append(HourlyBattery(row["at"], float(row["average"])))
    return {d: {m: sorted(points, key=lambda p: p.at) for m, points in by.items()} for d, by in series.items()}


def device_forecast(series: Mapping[str, list[HourlyBattery]] | None, now: datetime) -> Forecast | None:
    """The voltage forecast, or the level's for devices that report a level only; None without battery readings."""
    for metric in (VOLTAGE, LEVEL):
        points = (series or {}).get(metric)
        if points:
            result = forecast(points, metric, now)
            if result is not None:
                return result
    return None
