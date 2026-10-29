import json

import httpx
import pytest

from anomaly_detector.decision import Classification
from anomaly_detector.explanation import INSTRUCTIONS, Explainer

STATE = {"device": {"id": "paris-01-ruuvi5-001"}, "metric": {"name": "temperature", "unit": "°C"}}
CLASSIFICATION = Classification(
    {"real_event": 0.91, "sensor_fault": 0.05, "expected_pattern": 0.02, "cannot_tell": 0.02}, "microsoft-decision-1"
)
TEXT = "paris-01-ruuvi5-001 reported 35 °C instead of the usual 21 °C between 14:45 and 15:00 UTC."


def response(text=TEXT):
    return {
        "id": "resp_1",
        "object": "response",
        "created_at": 0,
        "model": "gpt-6-luna",
        "status": "completed",
        "output": [
            {
                "type": "message",
                "id": "msg_1",
                "role": "assistant",
                "status": "completed",
                "content": [{"type": "output_text", "text": text, "annotations": []}],
            }
        ],
        "usage": {"input_tokens": 300, "output_tokens": 80, "total_tokens": 380},
    }


def explainer(handler, requests):
    def record(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return handler(request)

    client = httpx.Client(transport=httpx.MockTransport(record))
    return Explainer("https://ais.openai.example/", "gpt-6-luna", token=lambda: "token", http_client=client)


def test_prompt_contains_only_the_decision_data():
    requests = []
    explanation = explainer(lambda _: httpx.Response(200, json=response()), requests).explain(STATE, CLASSIFICATION)
    assert explanation is not None
    assert (explanation.text, explanation.model) == (TEXT, "gpt-6-luna")
    request = requests[-1]
    assert str(request.url) == "https://ais.openai.example/openai/v1/responses"
    assert request.headers["Authorization"] == "Bearer token"
    body = json.loads(request.content)
    assert body["instructions"] == INSTRUCTIONS
    assert json.loads(body["input"]) == {
        "anomaly": STATE,
        "classification": "real event in the environment",
        "probability": 0.91,
    }
    assert body["store"] is False
    assert body["reasoning"] == {"effort": "low"}


@pytest.mark.parametrize(
    "failure",
    [
        httpx.Response(400, json={"error": {"message": "bad request"}}),
        httpx.Response(200, json=response("")),
    ],
)
def test_failure_leaves_no_explanation(failure):
    assert explainer(lambda _: failure, []).explain(STATE, CLASSIFICATION) is None


def test_network_failure_leaves_no_explanation():
    def down(request):
        raise httpx.ConnectError("down", request=request)

    assert explainer(down, []).explain(STATE, CLASSIFICATION) is None
