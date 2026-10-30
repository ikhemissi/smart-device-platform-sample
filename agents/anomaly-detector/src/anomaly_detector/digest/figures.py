"""Site digest figures (M8 design D3): everything a digest states, computed in code from the platform's data.

The database layer loads plain rows; `compute` turns them into the figures stored with the digest and given to the
language model. Lists are bounded so that the figures and the prompt stay small.
"""

from collections import Counter, defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

PERIOD = timedelta(hours=24)
COMPARISON = timedelta(days=7)
SILENCE = timedelta(minutes=15)
CERTIFICATE_WARNING = timedelta(days=30)
MAX_ITEMS = 20


@dataclass(frozen=True)
class Window:
    """The digest's period (the 24 hours before generation) and its comparison period (the 7 days before)."""

    generated_at: datetime

    @property
    def period_from(self) -> datetime:
        return self.generated_at - PERIOD

    @property
    def comparison_from(self) -> datetime:
        return self.period_from - COMPARISON


@dataclass(frozen=True)
class DeviceRow:
    device_id: str
    display_name: str | None
    last_seen_at: datetime


@dataclass(frozen=True)
class MetricRow:
    """Sum, count, min and max of one metric over one part of a period (rolled-up hours or recent raw readings)."""

    metric: str
    in_period: bool  # False: comparison period
    devices: int
    total: float
    count: int
    min_value: float
    max_value: float


@dataclass(frozen=True)
class SiteData:
    site_id: str
    site_name: str
    devices: list[DeviceRow]
    metrics: list[MetricRow]
    units: dict[str, str]
    alerts: list[dict[str, Any]]  # opened during the period
    open_alerts: int  # open at generation time, whenever they opened
    anomalies: list[dict[str, Any]]
    rejected: dict[str, int]
    certificates: list[dict[str, Any]]


def _iso(at: datetime | None) -> str | None:
    return at.isoformat(timespec="seconds").replace("+00:00", "Z") if at else None


def _round(value: float, digits: int = 2) -> float:
    return round(float(value), digits) + 0.0  # no "-0.0" in the figures


def _bounded(items: list[Any]) -> dict[str, Any]:
    return {"total": len(items), "items": items[:MAX_ITEMS]}


def _metrics(rows: Iterable[MetricRow], units: dict[str, str]) -> list[dict[str, Any]]:
    parts: dict[tuple[str, bool], list[MetricRow]] = defaultdict(list)
    for row in rows:
        parts[(row.metric, row.in_period)].append(row)
    figures = []
    for metric in sorted({m for m, _ in parts}):
        period, comparison = parts.get((metric, True), []), parts.get((metric, False), [])
        count = sum(r.count for r in period)
        if count == 0:
            continue
        average = sum(r.total for r in period) / count
        figure: dict[str, Any] = {
            "metric": metric,
            "unit": units.get(metric, ""),
            "devices": max(r.devices for r in period),
            "average": _round(average),
            "min": _round(min(r.min_value for r in period)),
            "max": _round(max(r.max_value for r in period)),
        }
        comparison_count = sum(r.count for r in comparison)
        if comparison_count:
            previous = sum(r.total for r in comparison) / comparison_count
            figure["comparisonAverage"] = _round(previous)
            figure["change"] = _round(average - previous)
            if previous:
                figure["changePercent"] = _round(100 * (average - previous) / abs(previous), 1)
        figures.append(figure)
    return figures


def compute(data: SiteData, window: Window) -> dict[str, Any]:
    at = window.generated_at
    silent = sorted((d for d in data.devices if at - d.last_seen_at > SILENCE), key=lambda d: d.last_seen_at)
    anomaly_status = Counter(a["status"] for a in data.anomalies)
    expiring = [c for c in data.certificates if c["notAfter"] - at <= CERTIFICATE_WARNING]
    return {
        "site": {"id": data.site_id, "name": data.site_name},
        "period": {"from": _iso(window.period_from), "to": _iso(at)},
        "comparison": {"from": _iso(window.comparison_from), "to": _iso(window.period_from)},
        "devices": {
            "total": len(data.devices),
            "reporting": len(data.devices) - len(silent),
            "silent": _bounded(
                [{"deviceId": d.device_id, "name": d.display_name, "lastSeenAt": _iso(d.last_seen_at)} for d in silent]
            ),
        },
        "metrics": _metrics(data.metrics, data.units),
        "alerts": {
            "openNow": data.open_alerts,
            **_bounded(
                [
                    {
                        "deviceId": a["deviceId"],
                        "metric": a["metric"],
                        "direction": a["direction"].lower(),
                        "status": a["status"].lower(),
                        "openedAt": _iso(a["openedAt"]),
                        "closedAt": _iso(a.get("closedAt")),
                        "worstValue": _round(float(a["worstValue"])),
                    }
                    for a in data.alerts
                ]
            ),
        },
        "anomalies": {
            "byStatus": dict(sorted(anomaly_status.items())),
            # Dismissed ones are counted, not listed: they are expected patterns.
            **_bounded(
                [
                    {
                        "deviceId": a["deviceId"],
                        "metric": a["metric"],
                        "kind": a["kind"],
                        "status": a["status"],
                        "classification": a["classification"],
                        "confidencePercent": round(100 * a["confidence"]) if a["confidence"] is not None else None,
                        "startedAt": _iso(a["startedAt"]),
                        "endedAt": _iso(a["endedAt"]),
                    }
                    for a in data.anomalies
                    if a["status"] != "dismissed"
                ]
            ),
        },
        "rejectedMessages": {"total": sum(data.rejected.values()), "byReason": dict(sorted(data.rejected.items()))},
        "certificates": {
            "expiringWithin30Days": [
                {
                    "slot": c["slot"],
                    "subject": c["subject"],
                    "notAfter": _iso(c["notAfter"]),
                    "daysLeft": max(0, (c["notAfter"] - at).days),
                }
                for c in sorted(expiring, key=lambda c: c["notAfter"])
            ]
        },
    }
