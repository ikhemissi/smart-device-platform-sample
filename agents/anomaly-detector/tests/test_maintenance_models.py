import json
from datetime import UTC, datetime, timedelta

import httpx
import pytest
import respx

from anomaly_detector.maintenance.battery import Forecast
from anomaly_detector.maintenance.models import (
    CRITERIA,
    INSTRUCTIONS,
    WORK_ORDER_INSTRUCTIONS,
    InterventionClient,
    WorkOrderWriter,
    assess,
    build_state,
)
from anomaly_detector.maintenance.signals import MaintenanceDevice, Period, Signals

from .tracing import EXPORTER

URL = "https://ais.example/providers/microsoft/v1/systemone"
NOW = datetime(2026, 10, 29, 12, 0, tzinfo=UTC)
DEVICE = MaintenanceDevice("paris-01-ruuvi5-003", "paris-01", "Cold room 3", "ruuvi-v5", NOW)
FORECAST = Forecast("battery_voltage", "V", 2.65, -0.05, 3.0, NOW - timedelta(days=5), 2.5)
SIGNALS = Signals(0.3, Period(2880, 30.0, 31.0), Period(20160, 30.0, 95.0), 0)


def state():
    return build_state(DEVICE, FORECAST, SIGNALS)


def answer(p):
    return {"model": "microsoft-decision-1", "answers": {"intervention": {"type": "noul", "noul": p}},
            "usage": {"input_tokens": 150, "output_tokens": 1}}  # fmt: skip


@pytest.fixture
def client():
    return InterventionClient("https://ais.example/", "decision-1", token=lambda: "token")


def test_state_holds_the_device_battery_and_signals_only():
    s = state()
    assert set(s) == {"device", "battery", "signals"}
    assert s["device"] == {"id": "paris-01-ruuvi5-003", "site": "paris-01", "payload_format": "ruuvi-v5"}
    assert s["battery"]["days_left"] == 3.0
    assert s["signals"]["last_24h"]["messages"] == 2880
    # A device without battery says so explicitly (a null battery read as a risk to the live decision model).
    assert build_state(DEVICE, None, SIGNALS)["battery"] == {"reported": False}


@respx.mock
def test_probability_parsed_and_request_shape(client):
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=answer(0.93)))
    EXPORTER.clear()
    result = assess(client, state())
    assert (result.value, result.model, result.version, result.input_tokens) == (0.93, "microsoft-decision-1", "1", 150)
    body = json.loads(route.calls.last.request.content)
    assert body["model"] == "decision-1"
    assert body["state"] == state()
    assert body["questions"] == {"intervention": {"type": "noul", "instructions": INSTRUCTIONS, "criteria": CRITERIA}}
    assert route.calls.last.request.headers["Authorization"] == "Bearer token"
    span = EXPORTER.get_finished_spans()[-1]
    assert span.name == "sdp.maintenance.decide"
    assert span.attributes["sdp.maintenance.probability"] == 0.93


@respx.mock
@pytest.mark.parametrize(
    "failure",
    [
        httpx.Response(429, json={"error": "throttled"}),
        httpx.Response(500),
        httpx.Response(200, json={"answers": {}}),
        httpx.Response(200, json=answer(1.7)),
        httpx.Response(200, text="not json"),
    ],
)
def test_failure_keeps_the_previous_assessment(client, failure):
    respx.post(URL).mock(return_value=failure)
    EXPORTER.clear()
    assert assess(client, state()) is None
    assert EXPORTER.get_finished_spans()[-1].attributes["sdp.maintenance.decision"] == "failed"


DATA = {
    "device": {"id": "paris-01-ruuvi5-003", "site": "paris-01", "name": "Cold room 3"},
    "battery": FORECAST.as_json(),
    "signals": SIGNALS.as_json(),
    "reasons": [{"rule": "battery_days_left", "value": 3.0, "threshold": 7, "unit": "days"}],
    "probability": 0.93,
}
TEXT = "Replace the battery of paris-01-ruuvi5-003 at paris-01: it is at 2.65 V and reaches 2.5 V in 3 days."


def response(text=TEXT):
    return {
        "id": "resp_1", "object": "response", "created_at": 0, "model": "gpt-6-luna", "status": "completed",
        "output": [{"type": "message", "id": "m", "role": "assistant", "status": "completed",
                    "content": [{"type": "output_text", "text": text, "annotations": []}]}],
        "usage": {"input_tokens": 400, "output_tokens": 60, "total_tokens": 460},
    }  # fmt: skip


def writer(handler, requests):
    def record(request):
        requests.append(request)
        return handler(request)

    http = httpx.Client(transport=httpx.MockTransport(record))
    return WorkOrderWriter("https://ais.openai.example/", "gpt-6-luna", token=lambda: "token", http_client=http)


def test_work_order_prompt_holds_the_figures_only():
    requests = []
    EXPORTER.clear()
    order = writer(lambda _: httpx.Response(200, json=response()), requests).write(DATA)
    assert (order.text, order.model) == (TEXT, "gpt-6-luna")
    body = json.loads(requests[-1].content)
    assert body["instructions"] == WORK_ORDER_INSTRUCTIONS
    assert json.loads(body["input"]) == DATA
    assert (body["store"], body["reasoning"]) == (False, {"effort": "low"})
    span = EXPORTER.get_finished_spans()[-1]
    assert span.name == "sdp.maintenance.work_order"
    assert span.attributes["sdp.maintenance.unsupported_numbers"] == 0
    assert span.attributes["gen_ai.usage.output_tokens"] == 60


def test_grounding_check_counts_numbers_not_in_the_input():
    EXPORTER.clear()
    text = "Replace the battery of paris-01-ruuvi5-003: 2.65 V, about 42 days left."
    order = writer(lambda _: httpx.Response(200, json=response(text)), []).write(DATA)
    assert order is not None
    assert EXPORTER.get_finished_spans()[-1].attributes["sdp.maintenance.unsupported_numbers"] == 1


@pytest.mark.parametrize(
    "failure", [httpx.Response(400, json={"error": {"message": "bad"}}), httpx.Response(200, json=response(""))]
)
def test_failure_leaves_no_work_order(failure):
    assert writer(lambda _: failure, []).write(DATA) is None
