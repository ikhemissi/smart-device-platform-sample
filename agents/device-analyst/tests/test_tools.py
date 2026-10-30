"""Task 3.2: tools call the platform API with the caller's token, compact and bound results, map errors."""

import json
from datetime import UTC, datetime

import httpx
import pytest
import respx

from device_analyst.platform_api import caller_token
from device_analyst.tools import TurnRecorder, build_tools, turn

from .conftest import API

NOW = datetime(2026, 10, 28, 12, 0, tzinfo=UTC)


@pytest.fixture
def tools(platform_api):
    return {t.name: t for t in build_tools(platform_api, now=lambda: NOW)}


@pytest.fixture(autouse=True)
def context():
    token = caller_token.set("caller-token")
    recorder = TurnRecorder()
    turn_token = turn.set(recorder)
    yield recorder
    turn.reset(turn_token)
    caller_token.reset(token)


async def call(tools, name: str, **arguments) -> str:
    return await tools[name].func(**arguments)


def data(result: str):
    assert result.startswith("<data>") and result.endswith("</data>"), result
    return json.loads(result[len("<data>") : -len("</data>")])


@respx.mock
async def test_forwards_the_callers_token_and_compacts(tools, context):
    route = respx.get(f"{API}/devices/paris-01-ruuvi5-001").respond(
        json={
            "deviceId": "paris-01-ruuvi5-001",
            "siteId": "paris-01",
            "displayName": None,
            "status": "reporting",
            "location": {"latitude": 48.856613, "longitude": 2.352222},
        }
    )
    respx.get(f"{API}/devices/paris-01-ruuvi5-001/metrics").respond(
        json=[
            {
                "metric": "temperature",
                "unit": "°C",
                "description": "Air temperature",
                "lastSeenAt": "2026-10-28T11:59:00Z",
            }
        ]
    )

    result = data(await call(tools, "get_device", device_id="paris-01-ruuvi5-001"))

    assert route.calls.last.request.headers["authorization"] == "Bearer caller-token"
    assert result["location"] == {"latitude": 48.857, "longitude": 2.352}
    assert "displayName" not in result
    assert result["metrics"] == [{"metric": "temperature", "unit": "°C"}]
    assert context.citations == [{"kind": "device", "deviceId": "paris-01-ruuvi5-001"}]
    assert context.calls == 2


@respx.mock
async def test_not_found_is_returned_to_the_model(tools, context):
    respx.get(f"{API}/devices/does-not-exist").respond(
        404, json={"title": "Not Found", "detail": "Device 'does-not-exist' does not exist."}
    )
    respx.get(f"{API}/devices").respond(json={"items": [], "page": 0, "pageSize": 5, "total": 0})

    result = json.loads(await call(tools, "get_device", device_id="does-not-exist"))

    assert result == {"error": "not found: Device 'does-not-exist' does not exist."}
    assert context.citations == []


@respx.mock
async def test_permission_validation_and_outage_errors(tools):
    respx.get(f"{API}/fleet/status").respond(403)
    respx.get(f"{API}/fleet/metric-summary").respond(400, json={"errors": {"metric": ["must be one of humidity"]}})
    respx.get(f"{API}/sites").mock(side_effect=httpx.ConnectError("down"))

    assert "not permitted" in await call(tools, "fleet_status")
    assert (
        json.loads(await call(tools, "metric_summary", metric="humid"))["error"]
        == "invalid request: metric must be one of humidity"
    )
    assert "unavailable" in await call(tools, "list_sites")


async def test_without_a_signed_in_user_nothing_is_called(tools):
    caller_token.set(None)
    assert "not permitted" in await call(tools, "list_sites")


@respx.mock
async def test_long_lists_are_truncated_explicitly(tools):
    respx.get(f"{API}/devices").respond(
        json={
            "items": [{"deviceId": f"d-{i}", "siteId": "s"} for i in range(50)],
            "page": 0,
            "pageSize": 50,
            "total": 300,
        }
    )

    result = data(await call(tools, "search_devices", site_id="s"))

    assert len(result["items"]) == 50
    assert result["truncated"] is True and result["total"] == 300


@respx.mock
async def test_series_are_summarised_with_the_cited_period(tools, context):
    route = respx.get(f"{API}/devices/d1/series").respond(
        json={
            "deviceId": "d1",
            "from": "2026-10-21T12:00:00Z",
            "to": "2026-10-28T12:00:00Z",
            "resolution": "6h",
            "series": [
                {
                    "metric": "temperature",
                    "unit": "°C",
                    "points": [
                        {"t": "2026-10-21T12:00:00Z", "min": 18.0, "avg": 19.0, "max": 20.0, "count": 10},
                        {"t": "2026-10-21T18:00:00Z", "min": 20.5, "avg": 22.0, "max": 24.25, "count": 30},
                    ],
                }
            ],
        }
    )

    result = data(
        await call(tools, "get_series", device_id="d1", metrics=["temperature"], from_time="2026-10-21T12:00:00Z")
    )

    params = route.calls.last.request.url.params
    assert params["from"] == "2026-10-21T12:00:00Z" and params["to"] == "2026-10-28T12:00:00Z"
    assert params["maxPoints"] == "60"
    series = result["series"][0]
    assert (series["min"], series["max"], series["first"], series["last"]) == (18.0, 24.25, 19.0, 22.0)
    assert series["avg"] == pytest.approx(21.25)
    assert context.citations == [
        {
            "kind": "device",
            "deviceId": "d1",
            "metrics": ["temperature"],
            "from": "2026-10-21T12:00:00Z",
            "to": "2026-10-28T12:00:00Z",
        }
    ]


@respx.mock
async def test_latest_measurements_are_bounded_and_drop_attributes(tools):
    route = respx.get(f"{API}/devices/d1/measurements/latest").respond(
        json=[
            {
                "measuredAt": "2026-10-28T11:59:30Z",
                "payloadFormat": "ruuvi-df5",
                "metrics": {"temperature": 21.123456},
                "attributes": {"mac": "aa:bb"},
            }
        ]
    )

    result = data(await call(tools, "get_latest_measurements", device_id="d1", count=20))

    assert route.calls.last.request.url.params["count"] == "20"
    assert result == {
        "deviceId": "d1",
        "measurements": [{"measuredAt": "2026-10-28T11:59:30Z", "metrics": {"temperature": 21.123}}],
    }


@respx.mock
async def test_device_alerts_filter_status_and_default_to_seven_days(tools):
    route = respx.get(f"{API}/devices/d1/alerts").respond(
        json=[
            {"id": 1, "status": "open", "metric": "temperature", "notifications": [{"kind": "opened"}]},
            {"id": 2, "status": "closed", "metric": "temperature"},
        ]
    )

    result = data(await call(tools, "list_alerts", device_id="d1", status="open"))

    assert route.calls.last.request.url.params["from"] == "2026-10-21T12:00:00Z"
    assert result["items"] == [{"id": 1, "status": "open", "metric": "temperature"}]


ANOMALY = {
    "id": 9,
    "deviceId": "paris-01-ruuvi5-001",
    "deviceName": None,
    "siteId": "paris-01",
    "metric": "temperature",
    "unit": "°C",
    "kind": "deviation",
    "startedAt": "2026-10-28T08:56:48Z",
    "endedAt": "2026-10-28T09:09:49Z",
    "observedMin": 20.512345,
    "observedMax": 32.3,
    "observedMean": 28.1,
    "observedLast": 32.0,
    "usualValue": 19.4,
    "spread": 0.2,
    "score": 42.2,
    "status": "confirmed",
    "decision": "confirmed",
    "classification": "real_event",
    "confidence": 0.7225,
    "probabilities": {"real_event": 0.7225, "sensor_fault": 0.25},
    "decisionModel": "microsoft-decision-1",
    "explanation": "Device paris-01-ruuvi5-001 warmed up.",
    "review": {"reviewedAt": "2026-10-28T10:00:00Z", "reviewedBy": "Erin", "verdict": "correct", "comment": None},
}


@pytest.fixture
def anomaly_tools(platform_api):
    return {t.name: t for t in build_tools(platform_api, now=lambda: NOW, anomalies=True)}


@respx.mock
async def test_site_anomalies_default_to_confirmed_and_to_review_over_seven_days(anomaly_tools, context):
    route = respx.get(f"{API}/anomalies").respond(json=[ANOMALY])

    result = data(await call(anomaly_tools, "list_anomalies", site_id="paris-01"))

    params = route.calls.last.request.url.params
    assert (params["siteId"], params["status"], params["from"]) == (
        "paris-01",
        "confirmed,to_review",
        "2026-10-21T12:00:00Z",
    )
    [item] = result["items"]
    assert (item["classification"], item["confidence"], item["status"]) == ("real_event", 0.723, "confirmed")
    assert item["observedMin"] == 20.512
    assert "probabilities" not in item and "decisionModel" not in item and "score" not in item
    assert item["review"] == {"verdict": "correct", "reviewedBy": "Erin"}
    assert context.citations == [
        {
            "kind": "device",
            "deviceId": "paris-01-ruuvi5-001",
            "metrics": ["temperature"],
            "from": "2026-10-28T08:56:48Z",
            "to": "2026-10-28T09:09:49Z",
        }
    ]


@respx.mock
async def test_device_anomalies_accept_a_name_and_statuses(anomaly_tools):
    respx.get(f"{API}/devices/Cold%20room/anomalies").respond(
        404, json={"detail": "Device 'Cold room' does not exist."}
    )
    respx.get(f"{API}/devices").respond(
        json={"items": [{"deviceId": "paris-01-ruuvi5-001", "displayName": "Cold room"}]}
    )
    route = respx.get(f"{API}/devices/paris-01-ruuvi5-001/anomalies").respond(json=[])

    result = data(
        await call(anomaly_tools, "list_anomalies", device_id="Cold room", status=["dismissed", "reviewed"],
                   from_time="2026-10-28T00:00:00Z")
    )  # fmt: skip

    assert route.calls.last.request.url.params["status"] == "dismissed,reviewed"
    assert route.calls.last.request.url.params["from"] == "2026-10-28T00:00:00Z"
    assert result["items"] == []


async def test_anomaly_tool_only_where_detection_runs(platform_api):
    assert "list_anomalies" not in {t.name for t in build_tools(platform_api)}
    assert "list_anomalies" in {t.name for t in build_tools(platform_api, anomalies=True)}


DIGEST = {
    "siteId": "lyon-01",
    "enabled": True,
    "digest": {
        "id": 4,
        "trigger": "requested",
        "periodFrom": "2026-10-29T15:55:21Z",
        "periodTo": "2026-10-30T15:55:21Z",
        "comparisonFrom": "2026-10-22T15:55:21Z",
        "generatedAt": "2026-10-30T15:55:21Z",
        "brief": "All 10 devices are reporting.",
        "briefModel": "gpt-6-luna",
        "requestedBy": "Erin",
        "figures": {
            "site": {"id": "lyon-01"},
            "period": {},
            "comparison": {},
            "devices": {"total": 10, "reporting": 10},
            "metrics": [{"metric": "temperature", "average": 20.123456}],
        },
    },
    "pendingRequest": None,
}


@respx.mock
async def test_site_digest_returns_the_brief_and_figures_and_cites_the_site(platform_api, context):
    tools = {t.name: t for t in build_tools(platform_api, now=lambda: NOW, digests=True)}
    respx.get(f"{API}/sites/lyon-01/digest").respond(json=DIGEST)

    result = data(await call(tools, "get_site_digest", site_id="lyon-01"))

    assert result["brief"] == "All 10 devices are reporting."
    assert (result["periodFrom"], result["generatedAt"]) == ("2026-10-29T15:55:21Z", "2026-10-30T15:55:21Z")
    assert result["figures"] == {
        "devices": {"total": 10, "reporting": 10},
        "metrics": [{"metric": "temperature", "average": 20.123}],
    }
    assert result["newDigestRequested"] is False
    assert context.citations == [{"kind": "site", "siteId": "lyon-01"}]


@respx.mock
async def test_site_without_digest(platform_api):
    tools = {t.name: t for t in build_tools(platform_api, now=lambda: NOW, digests=True)}
    respx.get(f"{API}/sites/nice-01/digest").respond(json={"siteId": "nice-01", "enabled": False, "digest": None})
    result = data(await call(tools, "get_site_digest", site_id="nice-01"))
    assert result["note"] == "this site has no digests in this environment"


async def test_digest_tool_only_where_digests_run(platform_api):
    assert "get_site_digest" not in {t.name for t in build_tools(platform_api)}
    assert "get_site_digest" in {t.name for t in build_tools(platform_api, digests=True)}


async def test_tools_are_read_only():
    names = {t.name for t in build_tools(None)}  # type: ignore[arg-type]
    assert names == {
        "list_sites",
        "fleet_status",
        "metric_summary",
        "search_devices",
        "get_device",
        "get_latest_measurements",
        "get_series",
        "list_alerts",
        "get_thresholds",
    }


@respx.mock
async def test_site_scoped_searches_cite_the_site(tools, context):
    respx.get(f"{API}/devices").respond(json={"items": [], "page": 0, "pageSize": 50, "total": 0})
    await call(tools, "search_devices", site_id="paris-01", status="reporting")
    assert context.citations == [{"kind": "site", "siteId": "paris-01"}]


@respx.mock
async def test_devices_can_be_named_instead_of_identified(tools, context):
    respx.get(f"{API}/devices/Lyon cold room/series").respond(
        404, json={"detail": "Device 'Lyon cold room' does not exist."}
    )
    search = respx.get(f"{API}/devices").respond(
        json={"items": [{"deviceId": "lyon-01-cayenne-001", "displayName": "Lyon cold room"}], "total": 1}
    )
    respx.get(f"{API}/devices/lyon-01-cayenne-001/series").respond(
        json={"deviceId": "lyon-01-cayenne-001", "from": "a", "to": "b", "series": []}
    )

    result = data(await call(tools, "get_series", device_id="Lyon cold room", metrics=["temperature"]))

    assert search.calls.last.request.url.params["search"] == "Lyon cold room"
    assert result["deviceId"] == "lyon-01-cayenne-001"
    assert context.citations[0]["deviceId"] == "lyon-01-cayenne-001"


@respx.mock
async def test_ambiguous_names_list_the_candidates(tools):
    respx.get(f"{API}/devices/cold room/thresholds").respond(404)
    respx.get(f"{API}/devices").respond(
        json={
            "items": [
                {"deviceId": "lyon-01-cayenne-001", "displayName": "Lyon cold room"},
                {"deviceId": "paris-01-cayenne-002", "displayName": "Paris cold room"},
            ],
            "total": 2,
        }
    )

    result = json.loads(await call(tools, "get_thresholds", device_id="cold room"))

    assert result["error"].startswith("ambiguous: 'cold room' matches several devices: lyon-01-cayenne-001 (Lyon")


MAINTENANCE_ITEM = {
    "id": 7,
    "deviceId": "paris-01-ruuvi5-001",
    "deviceName": "Cold room",
    "siteId": "paris-01",
    "status": "open",
    "probability": 0.81234,
    "reasons": [
        {"rule": "battery_days_left", "value": 4.6, "threshold": 7, "unit": "days", "level": 2.6, "level_unit": "V"}
    ],
    "battery": {
        "metric": "battery_voltage",
        "unit": "V",
        "level": 2.6,
        "slope_per_day": -0.0219,
        "days_left": 4.6,
        "since": "2026-10-20T00:00:00Z",
        "cutoff": 2.5,
    },
    "workOrder": "Replace the battery of Cold room within 4 days.",
    "workOrderModel": "gpt-6-luna",
    "workOrderAt": "2026-10-28T11:00:00Z",
    "openedAt": "2026-10-27T11:00:00Z",
    "updatedAt": "2026-10-28T11:00:00Z",
    "resolvedAt": None,
    "statusBy": None,
    "statusAt": None,
    "statusComment": None,
}


@pytest.fixture
def maintenance_tools(platform_api):
    return {t.name: t for t in build_tools(platform_api, now=lambda: NOW, maintenance=True)}


@respx.mock
async def test_maintenance_queue_defaults_to_open_and_planned_and_cites_the_devices(maintenance_tools, context):
    route = respx.get(f"{API}/maintenance").respond(json=[MAINTENANCE_ITEM])

    result = data(await call(maintenance_tools, "list_maintenance", site_id="paris-01"))

    params = route.calls.last.request.url.params
    assert (params["siteId"], params["status"], params["limit"]) == ("paris-01", "open,planned", "50")
    assert route.calls.last.request.headers["Authorization"] == "Bearer caller-token"
    [item] = result["items"]
    assert (item["deviceId"], item["probability"], item["status"]) == ("paris-01-ruuvi5-001", 0.812, "open")
    assert item["battery"] == {"metric": "battery_voltage", "unit": "V", "level": 2.6, "slope_per_day": -0.022,
                               "days_left": 4.6, "cutoff": 2.5}  # fmt: skip
    assert item["reasons"][0]["rule"] == "battery_days_left"
    assert item["workOrder"].startswith("Replace the battery")
    assert "workOrderModel" not in item and "updatedAt" not in item
    assert context.citations == [{"kind": "device", "deviceId": "paris-01-ruuvi5-001", "metrics": ["battery_voltage"]}]


@respx.mock
async def test_empty_maintenance_queue_of_a_site_cites_the_site(maintenance_tools, context):
    route = respx.get(f"{API}/maintenance").respond(json=[])

    result = data(await call(maintenance_tools, "list_maintenance", site_id="lyon-01", status=["done", "resolved"]))

    assert route.calls.last.request.url.params["status"] == "done,resolved"
    assert result["items"] == [] and result["status"] == ["done", "resolved"]
    assert context.citations == [{"kind": "site", "siteId": "lyon-01"}]


@respx.mock
async def test_maintenance_queue_without_battery_and_errors(maintenance_tools, context):
    respx.get(f"{API}/maintenance").respond(json=[{**MAINTENANCE_ITEM, "battery": None}])
    [item] = data(await call(maintenance_tools, "list_maintenance"))["items"]
    assert "battery" not in item
    assert context.citations == [{"kind": "device", "deviceId": "paris-01-ruuvi5-001"}]

    respx.get(f"{API}/maintenance").respond(status_code=403, json={"title": "Forbidden", "status": 403})
    assert "error" in json.loads(await call(maintenance_tools, "list_maintenance"))


async def test_maintenance_tool_only_where_maintenance_runs(platform_api):
    assert "list_maintenance" not in {t.name for t in build_tools(platform_api)}
    assert "list_maintenance" in {t.name for t in build_tools(platform_api, maintenance=True)}
