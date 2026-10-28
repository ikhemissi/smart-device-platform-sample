"""Read-only tools of the device analyst (M6 design D3). Each calls one or two platform API operations, compacts the
result (rounded numbers, no raw attributes, bounded lists with an explicit truncation marker) and records what it
consulted: a `tool` activity for the stream and citations for the answer. Errors are returned to the model as data."""

import asyncio
import json
from collections.abc import Callable
from contextvars import ContextVar
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Annotated, Any, Literal

from agent_framework import FunctionTool, tool
from pydantic import Field

from .platform_api import ApiError, PlatformApi, segment

MAX_ITEMS = 50
SERIES_POINTS = 60
LATEST_MAX = 20


@dataclass
class TurnRecorder:
    """What the tools of one turn consulted. Citations are deduplicated, in order of first use."""

    citations: list[dict[str, Any]] = field(default_factory=list)
    calls: int = 0

    def cite(self, citation: dict[str, Any]) -> None:
        clean = {k: v for k, v in citation.items() if v not in (None, [], "")}
        if clean not in self.citations:
            self.citations.append(clean)


turn: ContextVar[TurnRecorder | None] = ContextVar("turn", default=None)


def _recorder() -> TurnRecorder:
    recorder = turn.get()
    if recorder is None:
        recorder = TurnRecorder()
        turn.set(recorder)
    return recorder


def compact(value: Any, digits: int = 3) -> Any:
    """Rounds floats and drops nulls, recursively."""
    if isinstance(value, float):
        return round(value, digits)
    if isinstance(value, dict):
        return {k: compact(v, digits) for k, v in value.items() if v is not None}
    if isinstance(value, list):
        return [compact(v, digits) for v in value]
    return value


def bounded(items: list[Any], limit: int = MAX_ITEMS, total: int | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"items": items[:limit]}
    total = total if total is not None else len(items)
    if total > limit or len(items) > limit:
        result["truncated"] = True
        result["total"] = total
    return result


def as_data(value: Any) -> str:
    # Delimits API data so instructions found inside it are not mistaken for the user's (design risk: prompt injection).
    return "<data>" + json.dumps(compact(value), ensure_ascii=False, separators=(",", ":")) + "</data>"


def error(e: ApiError) -> str:
    return json.dumps({"error": str(e)})


def parse_time(value: str | None, default: datetime) -> datetime:
    if not value:
        return default
    parsed = datetime.fromisoformat(value)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def iso(at: datetime) -> str:
    return at.astimezone(UTC).isoformat().replace("+00:00", "Z")


Iso = Annotated[str | None, Field(description="ISO 8601 UTC time, e.g. 2026-10-28T08:00:00Z")]


def build_tools(api: PlatformApi, now: Callable[[], datetime] = lambda: datetime.now(UTC)) -> list[FunctionTool]:
    """The tools, bound to a platform API client. Times default to now and the previous 24 hours."""

    def window(
        from_time: str | None, to_time: str | None, default: timedelta = timedelta(hours=24)
    ) -> tuple[datetime, datetime]:
        to = parse_time(to_time, now())
        return parse_time(from_time, to - default), to

    async def call(path: str, params: dict[str, Any] | None = None) -> Any:
        _recorder().calls += 1
        return await api.get(path, params)

    @tool(description="List the sites with their number of devices.")
    async def list_sites() -> str:
        try:
            sites = await call("/sites")
        except ApiError as e:
            return error(e)
        return as_data(bounded(sites))

    @tool(
        description="Per site: number of devices, reporting devices, silent devices (not seen for 15 minutes) and "
        "the most recently seen silent devices with their last-seen time."
    )
    async def fleet_status(site_id: Annotated[str | None, Field(description="Restrict to one site")] = None) -> str:
        try:
            status = await call("/fleet/status", {"siteId": site_id})
        except ApiError as e:
            return error(e)
        for site in status:
            if site_id:
                _recorder().cite({"kind": "site", "siteId": site["siteId"]})
            site["silentDevices"] = bounded(site.get("silentDevices", []), 20, site.get("silent"))
        return as_data(status)

    @tool(
        description="Summary of one metric over a period (at most 31 days) per site or per device: min, avg, max, "
        "sample count, device count, first and last value with their times. Use it to compare sites or "
        "devices, or to check a metric across a fleet, instead of reading devices one by one."
    )
    async def metric_summary(
        metric: Annotated[str, Field(description="Canonical metric, e.g. temperature, humidity, battery_voltage")],
        from_time: Iso = None,
        to_time: Iso = None,
        group_by: Annotated[Literal["site", "device"], Field(description="site or device")] = "site",
        site_id: Annotated[str | None, Field(description="Restrict to one site")] = None,
    ) -> str:
        start, end = window(from_time, to_time)
        try:
            summary = await call(
                "/fleet/metric-summary",
                {"metric": metric, "from": iso(start), "to": iso(end), "groupBy": group_by, "siteId": site_id},
            )
        except ApiError as e:
            return error(e)
        if site_id:
            _recorder().cite({"kind": "site", "siteId": site_id})
        summary["groups"] = bounded(summary["groups"])
        return as_data(summary)

    @tool(
        description="Search devices by site, status (reporting or silent) and/or text in their id or name. Returns "
        "at most 50 devices with their site, status and last-seen time."
    )
    async def search_devices(
        site_id: Annotated[str | None, Field(description="Site id, e.g. paris-01")] = None,
        query: Annotated[str | None, Field(description="Text to find in the device id or name")] = None,
        status: Annotated[Literal["reporting", "silent"] | None, Field(description="reporting or silent")] = None,
    ) -> str:
        try:
            page = await call("/devices", {"siteId": site_id, "search": query, "status": status, "pageSize": MAX_ITEMS})
        except ApiError as e:
            return error(e)
        if site_id:
            _recorder().cite({"kind": "site", "siteId": site_id})
        return as_data(bounded(page["items"], total=page.get("total")))

    @tool(
        description="Details of one device (name, site, location, status, first/last seen) and the metrics it "
        "reports with their units."
    )
    async def get_device(device_id: Annotated[str, Field(description="Exact device id")]) -> str:
        try:
            device, metrics = await asyncio.gather(
                call(f"/devices/{segment(device_id)}"), call(f"/devices/{segment(device_id)}/metrics")
            )
        except ApiError as e:
            return error(e)
        _recorder().cite({"kind": "device", "deviceId": device_id})
        device["metrics"] = [{"metric": m["metric"], "unit": m.get("unit")} for m in metrics]
        return as_data(device)

    @tool(description="The latest measurements of a device, most recent first (at most 20).")
    async def get_latest_measurements(
        device_id: Annotated[str, Field(description="Exact device id")],
        count: Annotated[int, Field(ge=1, le=LATEST_MAX, description="Number of measurements, 1 to 20")] = 5,
    ) -> str:
        try:
            measurements = await call(
                f"/devices/{segment(device_id)}/measurements/latest", {"count": min(count, LATEST_MAX)}
            )
        except ApiError as e:
            return error(e)
        _recorder().cite({"kind": "device", "deviceId": device_id})
        return as_data([{"measuredAt": m["measuredAt"], "metrics": m["metrics"]} for m in measurements])

    @tool(
        description="Evolution of 1 to 4 metrics of a device over a period (at most 31 days): statistics per metric "
        "(min, avg, max, first, last) and up to 60 points (bucket averages for long periods). Use it for "
        "trend questions."
    )
    async def get_series(
        device_id: Annotated[str, Field(description="Exact device id")],
        metrics: Annotated[list[str], Field(min_length=1, max_length=4, description="Metric keys")],
        from_time: Iso = None,
        to_time: Iso = None,
    ) -> str:
        start, end = window(from_time, to_time)
        try:
            series = await call(
                f"/devices/{segment(device_id)}/series",
                {"metrics": ",".join(metrics), "from": iso(start), "to": iso(end), "maxPoints": SERIES_POINTS},
            )
        except ApiError as e:
            return error(e)
        _recorder().cite(
            {"kind": "device", "deviceId": device_id, "metrics": metrics, "from": iso(start), "to": iso(end)}
        )
        return as_data(
            {
                "deviceId": device_id,
                "from": series["from"],
                "to": series["to"],
                "resolution": series.get("resolution"),
                "series": [_summarise(s) for s in series["series"]],
            }
        )

    @tool(
        description="Threshold alerts, most recent first, optionally for one site or one device, by status "
        "(open or closed) and period (default: last 7 days)."
    )
    async def list_alerts(
        site_id: Annotated[str | None, Field(description="Site id")] = None,
        device_id: Annotated[str | None, Field(description="Exact device id")] = None,
        status: Annotated[Literal["open", "closed"] | None, Field(description="open or closed")] = None,
        from_time: Iso = None,
        to_time: Iso = None,
    ) -> str:
        start, end = window(from_time, to_time, timedelta(days=7))
        params = {"from": iso(start), "to": iso(end), "limit": MAX_ITEMS}
        try:
            if device_id:
                alerts = await call(f"/devices/{segment(device_id)}/alerts", params)
                if status:
                    alerts = [a for a in alerts if a.get("status") == status]
            else:
                alerts = await call("/alerts", {**params, "siteId": site_id, "status": status})
        except ApiError as e:
            return error(e)
        if device_id:
            _recorder().cite({"kind": "device", "deviceId": device_id})
        return as_data(bounded([{k: v for k, v in a.items() if k != "notifications"} for a in alerts]))

    @tool(description="Alert thresholds configured for a device (metric, min and/or max).")
    async def get_thresholds(device_id: Annotated[str, Field(description="Exact device id")]) -> str:
        try:
            thresholds = await call(f"/devices/{segment(device_id)}/thresholds")
        except ApiError as e:
            return error(e)
        return as_data(thresholds)

    return [
        list_sites,
        fleet_status,
        metric_summary,
        search_devices,
        get_device,
        get_latest_measurements,
        get_series,
        list_alerts,
        get_thresholds,
    ]


def _summarise(series: dict[str, Any]) -> dict[str, Any]:
    """Statistics of a series (raw points or min/avg/max/count buckets) and its points as [time, value] pairs."""
    points = [p for p in series.get("points") or [] if p.get("value") is not None or p.get("avg") is not None]
    result: dict[str, Any] = {"metric": series["metric"], "unit": series.get("unit"), "points": len(points)}
    if not points:
        return result
    raw = points[0].get("value") is not None
    values = [p["value"] if raw else p["avg"] for p in points]
    weights = [1 if raw else p.get("count") or 1 for p in points]
    return result | {
        "min": min(values) if raw else min(p["min"] for p in points),
        "max": max(values) if raw else max(p["max"] for p in points),
        "avg": sum(v * w for v, w in zip(values, weights, strict=True)) / sum(weights),
        "first": values[0],
        "last": values[-1],
        "values": [[p["t"], v] for p, v in zip(points, values, strict=True)],
    }
