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
