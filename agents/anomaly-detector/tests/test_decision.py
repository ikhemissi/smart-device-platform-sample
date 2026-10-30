import json
from datetime import UTC, datetime, timedelta

import httpx
import pytest
import respx

from anomaly_detector.db import Device
from anomaly_detector.decision import (
    OPTIONS,
    DecisionClient,
    build_state,
    decide,
)
from anomaly_detector.detection import Assessment, Candidate

ENDPOINT = "https://ais.example/"
URL = "https://ais.example/providers/microsoft/v1/systemone"
NOW = datetime(2026, 10, 29, 15, 0, tzinfo=UTC)
DEVICE = Device("paris-01-ruuvi5-001", "paris-01", "Cold room 1", "ruuvi-v5")


def assessment(device_id=DEVICE.device_id, metric="temperature", score=60.0):
    return Assessment(
        device_id, metric, NOW - timedelta(minutes=15), NOW, 30, 21, 35, 33.4, 35, 21.2, 0.2, score, 13.8, False
    )


def state():
    neighbours = {"paris-01-ruuvi5-002": 0.4, "lyon-01-ruuvi5-001": 9.0}
    candidate = Candidate("deviation", assessment(), neighbours)
    return build_state(candidate, DEVICE, "°C", [DEVICE.device_id, "paris-01-ruuvi5-002"])


def answer(**probabilities):
    p = dict.fromkeys(OPTIONS, 0.0) | probabilities
    top = max(p, key=p.get)
    return {
        "model": "microsoft-decision-1",
        "answers": {"classification": {"type": "choice", "choice": top, "confidence": p[top], "probabilities": p}},
        "usage": {"input_tokens": 190, "output_tokens": 1},
    }


@pytest.fixture
def client():
    return DecisionClient(ENDPOINT, "decision-1", token=lambda: "token")


@respx.mock
@pytest.mark.parametrize(
    "probabilities,status,label",
    [
        ({"real_event": 0.91, "sensor_fault": 0.05}, "confirmed", "real_event"),
        ({"sensor_fault": 0.85}, "confirmed", "sensor_fault"),
        ({"expected_pattern": 0.9}, "dismissed", "expected_pattern"),
        ({"real_event": 0.55, "sensor_fault": 0.45}, "to_review", "real_event"),
        ({"cannot_tell": 0.95}, "to_review", "cannot_tell"),
    ],
)
def test_status_from_confidence(client, probabilities, status, label):
    respx.post(URL).respond(json=answer(**probabilities))
    decision = decide(client, state(), confidence=0.8)
    assert (decision.status, decision.label) == (status, label)
    assert set(decision.classification.probabilities) == set(OPTIONS)


@respx.mock
def test_request_names_the_deployment_and_the_four_options(client):
    route = respx.post(URL).respond(json=answer(real_event=0.9))
    decide(client, state(), confidence=0.8)
    request = route.calls.last.request
    body = json.loads(request.content)
    assert request.headers["Authorization"] == "Bearer token"
    assert body["model"] == "decision-1"
    question = body["questions"]["classification"]
    assert question["type"] == "choice"
    assert set(question["criteria"]) == {"real_event", "sensor_fault", "expected_pattern", "cannot_tell"}
    assert body["state"]["device"]["id"] == DEVICE.device_id


@respx.mock
def test_failure_leaves_the_candidate_pending_then_the_retry_classifies_it(client):
    respx.post(URL).mock(side_effect=[httpx.Response(429), httpx.Response(200, json=answer(real_event=0.9))])
    assert decide(client, state(), confidence=0.8).status == "pending"
    assert decide(client, state(), confidence=0.8).status == "confirmed"


@respx.mock
@pytest.mark.parametrize("response", [httpx.Response(200, json={"answers": {}}), httpx.Response(200, text="oops")])
def test_malformed_response_leaves_the_candidate_pending(client, response):
    respx.post(URL).mock(return_value=response)
    assert decide(client, state(), confidence=0.8).status == "pending"


@respx.mock
def test_network_error_leaves_the_candidate_pending(client):
    respx.post(URL).mock(side_effect=httpx.ConnectTimeout("timeout"))
    assert decide(client, state(), confidence=0.8).status == "pending"


def test_state_holds_data_only_and_same_metric_neighbours():
    s = state()
    assert s["metric"] == {"name": "temperature", "unit": "°C"}
    assert s["usual"] == {"value": 21.2, "spread": 0.2, "range_within_the_hour": None}
    assert s["same_metric_on_other_devices_of_site"] == [{"device": "paris-01-ruuvi5-002", "score": 0.4}]
    assert s["window"]["max"] == 35
