"""Read-only tools of the device analyst (M6 design D3). Each calls one or two platform API operations, compacts the
result (rounded numbers, no raw attributes, bounded lists with an explicit truncation marker) and records what it
consulted: a `tool` activity for the stream and citations for the answer. Errors are returned to the model as data."""

import json
from collections.abc import Callable
from contextvars import ContextVar
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Annotated, Any, Literal

from agent_framework import FunctionTool, tool
from opentelemetry import trace
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
    # The current span is the Agent Framework's execute_tool span: the failure kind makes tool errors countable.
    trace.get_current_span().set_attribute("sdp.agent.tool.error", e.kind)
    return json.dumps({"error": str(e)})


def parse_time(value: str | None, default: datetime) -> datetime:
    if not value:
        return default
    parsed = datetime.fromisoformat(value)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def iso(at: datetime) -> str:
    return at.astimezone(UTC).isoformat().replace("+00:00", "Z")


Iso = Annotated[str | None, Field(description="ISO 8601 UTC time, e.g. 2026-10-28T08:00:00Z")]
DeviceRef = Annotated[str, Field(description="Device id (e.g. paris-01-ruuvi5-001) or its name (e.g. Lyon cold room)")]


# What the assistant needs of an anomaly (M7 design D9): the record without model internals.
ANOMALY_FIELDS = (
    "id", "deviceId", "deviceName", "siteId", "metric", "unit", "kind", "startedAt", "endedAt", "observedMin",
    "observedMax", "observedLast", "usualValue", "status", "classification", "confidence", "explanation", "review",
)  # fmt: skip


# What the assistant needs of a maintenance item (M9 design D10): the computed figures, the work order and the status.
MAINTENANCE_FIELDS = (
    "id", "deviceId", "deviceName", "siteId", "status", "probability", "reasons", "workOrder", "openedAt", "statusBy",
    "statusAt", "statusComment",
)  # fmt: skip
BATTERY_FIELDS = ("metric", "unit", "level", "slope_per_day", "days_left", "cutoff")


def build_tools(
    api: PlatformApi,
    now: Callable[[], datetime] = lambda: datetime.now(UTC),
    anomalies: bool = False,
    digests: bool = False,
    maintenance: bool = False,
) -> list[FunctionTool]:
    """The tools, bound to a platform API client. Times default to now and the previous 24 hours. The anomaly, digest
    and maintenance tools are available only where anomaly detection (M7), site digests (M8) and predictive
    maintenance (M9) run."""

    def window(
        from_time: str | None, to_time: str | None, default: timedelta = timedelta(hours=24)
    ) -> tuple[datetime, datetime]:
        to = parse_time(to_time, now())
        return parse_time(from_time, to - default), to

    async def call(path: str, params: dict[str, Any] | None = None) -> Any:
        _recorder().calls += 1
        return await api.get(path, params)

    async def device_call(ref: str, suffix: str = "", params: dict[str, Any] | None = None) -> tuple[str, Any]:
        """Calls a device operation with a device id or, when no device has that id, its unique name match: one tool
        round instead of a search followed by a read. Returns the device id used and the result."""
        try:
            return ref, await call(f"/devices/{segment(ref)}{suffix}", params)
        except ApiError as e:
            if not str(e).startswith("not found"):
                raise
            matches = (await call("/devices", {"search": ref, "pageSize": 5}))["items"]
            if not matches:
                raise
            if len(matches) > 1:
                names = ", ".join(f"{m['deviceId']} ({m.get('displayName') or 'no name'})" for m in matches)
                raise ApiError(
                    f"ambiguous: '{ref}' matches several devices: {names}; use an exact device id", "invalid_request"
                ) from e
            device_id = matches[0]["deviceId"]
            return device_id, await call(f"/devices/{segment(device_id)}{suffix}", params)

    @tool(description="List the sites with their number of devices.")
    async def list_sites() -> str:
        try:
            sites = await call("/sites")
        except ApiError as e:
            return error(e)
        return as_data(bounded(sites))

    @tool(
        description="Per site: number of devices, reporting devices, silent devices (not seen for 15 minutes) and "
        "the silent devices with their last-seen time (complete up to 100 per site, most recently seen first)."
    )
    async def fleet_status(site_id: Annotated[str | None, Field(description="Restrict to one site")] = None) -> str:
        try:
            status = await call("/fleet/status", {"siteId": site_id})
        except ApiError as e:
            return error(e)
        for site in status:
            if site_id:
                _recorder().cite({"kind": "site", "siteId": site["siteId"]})
            site["silentDevices"] = bounded(site.get("silentDevices", []), 100, site.get("silent"))
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
    async def get_device(device_id: DeviceRef) -> str:
        try:
            device_id, device = await device_call(device_id)
            metrics = await call(f"/devices/{segment(device_id)}/metrics")
        except ApiError as e:
            return error(e)
        _recorder().cite({"kind": "device", "deviceId": device_id})
        device["metrics"] = [{"metric": m["metric"], "unit": m.get("unit")} for m in metrics]
        return as_data(device)

    @tool(description="The latest measurements of a device, most recent first (at most 20).")
    async def get_latest_measurements(
        device_id: DeviceRef,
        count: Annotated[int, Field(ge=1, le=LATEST_MAX, description="Number of measurements, 1 to 20")] = 5,
    ) -> str:
        try:
            device_id, measurements = await device_call(
                device_id, "/measurements/latest", {"count": min(count, LATEST_MAX)}
            )
        except ApiError as e:
            return error(e)
        _recorder().cite({"kind": "device", "deviceId": device_id})
        latest = [{"measuredAt": m["measuredAt"], "metrics": m["metrics"]} for m in measurements]
        return as_data({"deviceId": device_id, "measurements": latest})

    @tool(
        description="Evolution of 1 to 4 metrics of a device over a period (at most 31 days): statistics per metric "
        "(min, avg, max, first, last) and up to 60 points (bucket averages for long periods). Use it for "
        "trend questions."
    )
    async def get_series(
        device_id: DeviceRef,
        metrics: Annotated[list[str], Field(min_length=1, max_length=4, description="Metric keys")],
        from_time: Iso = None,
        to_time: Iso = None,
    ) -> str:
        start, end = window(from_time, to_time)
        try:
            device_id, series = await device_call(
                device_id,
                "/series",
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
        device_id: Annotated[str | None, Field(description="Device id or name")] = None,
        status: Annotated[Literal["open", "closed"] | None, Field(description="open or closed")] = None,
        from_time: Iso = None,
        to_time: Iso = None,
    ) -> str:
        start, end = window(from_time, to_time, timedelta(days=7))
        params = {"from": iso(start), "to": iso(end), "limit": MAX_ITEMS}
        try:
            if device_id:
                device_id, alerts = await device_call(device_id, "/alerts", params)
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
    async def get_thresholds(device_id: DeviceRef) -> str:
        try:
            device_id, thresholds = await device_call(device_id, "/thresholds")
        except ApiError as e:
            return error(e)
        return as_data({"deviceId": device_id, "thresholds": thresholds})

    @tool(
        description="Anomalies found by the platform's anomaly detection on the analysed sites, most recent first: "
        "unusual readings classified as real_event, sensor_fault, expected_pattern or cannot_tell with a confidence "
        "(0-1), status confirmed, to_review (the platform was unsure), dismissed or reviewed (an Editor gave a "
        "verdict), and an explanation. Filter by site, device, status and period (default: last 7 days; default "
        "statuses: confirmed and to_review)."
    )
    async def list_anomalies(
        site_id: Annotated[str | None, Field(description="Site id")] = None,
        device_id: Annotated[str | None, Field(description="Device id or name")] = None,
        status: Annotated[
            list[Literal["confirmed", "to_review", "dismissed", "reviewed"]] | None,
            Field(description="Statuses to include (default: confirmed and to_review)"),
        ] = None,
        from_time: Iso = None,
        to_time: Iso = None,
    ) -> str:
        start, end = window(from_time, to_time, timedelta(days=7))
        params: dict[str, Any] = {"from": iso(start), "to": iso(end), "limit": MAX_ITEMS}
        statuses = ",".join(status or ["confirmed", "to_review"])
        try:
            if device_id:
                device_id, found = await device_call(device_id, "/anomalies", {**params, "status": statuses})
            else:
                found = await call("/anomalies", {**params, "siteId": site_id, "status": statuses})
        except ApiError as e:
            return error(e)
        items = []
        for a in found:
            _recorder().cite(
                {"kind": "device", "deviceId": a["deviceId"], "metrics": [a["metric"]], "from": a["startedAt"],
                 "to": a["endedAt"]}
            )  # fmt: skip
            item = {k: a.get(k) for k in ANOMALY_FIELDS}
            if item["review"]:
                item["review"] = {k: item["review"].get(k) for k in ("verdict", "reviewedBy", "comment")}
            items.append(item)
        if site_id and not items:
            _recorder().cite({"kind": "site", "siteId": site_id})
        return as_data({"from": iso(start), "to": iso(end), **bounded(items)})

    @tool(
        description="The latest daily digest of a site: a short brief written by the platform from computed "
        "figures (devices reporting and silent, metric averages over the last 24 hours compared with the 7 days "
        "before, alerts, anomalies, rejected messages, certificates expiring soon), with its period and generation "
        "time. Use it for 'what happened at <site>' questions."
    )
    async def get_site_digest(site_id: Annotated[str, Field(description="Site id, e.g. lyon-01")]) -> str:
        try:
            result = await call(f"/sites/{segment(site_id)}/digest")
        except ApiError as e:
            return error(e)
        _recorder().cite({"kind": "site", "siteId": site_id})
        digest = result.get("digest")
        if not result.get("enabled"):
            return as_data({"siteId": site_id, "digest": None, "note": "this site has no digests in this environment"})
        if digest is None:
            return as_data({"siteId": site_id, "digest": None, "note": "no digest yet"})
        return as_data(
            {
                "siteId": site_id,
                "periodFrom": digest["periodFrom"],
                "periodTo": digest["periodTo"],
                "generatedAt": digest["generatedAt"],
                "brief": digest.get("brief"),
                "figures": {k: v for k, v in digest["figures"].items() if k not in ("site", "period", "comparison")},
                "newDigestRequested": result.get("pendingRequest") is not None,
            }
        )

    @tool(
        description="The maintenance queue: devices likely to need an on-site intervention within 7 days, most likely "
        "first, with the probability (0-1) from the platform's decision model, the battery forecast (level, trend per "
        "day, days_left before the cut-off), the reasons (computed figures that crossed a rule: battery_days_left, "
        "silent, reporting_gaps, rejected_messages; or battery_forecast and probability when none did), the work order "
        "and the status: open, planned (an Editor planned the visit), done (an Editor closed it) or resolved (the "
        "platform closed it). Filter by site and status (default: open and planned)."
    )
    async def list_maintenance(
        site_id: Annotated[str | None, Field(description="Site id")] = None,
        status: Annotated[
            list[Literal["open", "planned", "done", "resolved"]] | None,
            Field(description="Statuses to include (default: open and planned)"),
        ] = None,
    ) -> str:
        statuses = ",".join(status or ["open", "planned"])
        try:
            found = await call("/maintenance", {"siteId": site_id, "status": statuses, "limit": MAX_ITEMS})
        except ApiError as e:
            return error(e)
        items = []
        for m in found:
            battery = m.get("battery")
            metrics = [battery["metric"]] if battery else []
            _recorder().cite({"kind": "device", "deviceId": m["deviceId"], "metrics": metrics})
            item = {k: m.get(k) for k in MAINTENANCE_FIELDS}
            item["battery"] = {k: battery.get(k) for k in BATTERY_FIELDS} if battery else None
            items.append(item)
        if site_id and not items:
            _recorder().cite({"kind": "site", "siteId": site_id})
        return as_data({"status": statuses.split(","), **bounded(items)})

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
        *([list_anomalies] if anomalies else []),
        *([get_site_digest] if digests else []),
        *([list_maintenance] if maintenance else []),
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
