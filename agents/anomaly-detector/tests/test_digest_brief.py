import json

import httpx
import pytest

from anomaly_detector.digest.brief import INSTRUCTIONS, Briefer, unsupported_numbers

FIGURES = {
    "site": {"id": "paris-01", "name": "Paris"},
    "period": {"from": "2026-10-29T06:00:00Z", "to": "2026-10-30T06:00:00Z"},
    "devices": {"total": 10, "reporting": 9, "silent": {"total": 1, "items": [
        {"deviceId": "paris-01-ruuvi3-001", "name": None, "lastSeenAt": "2026-10-30T03:10:00Z"}]}},
    "metrics": [{"metric": "temperature", "unit": "°C", "average": 20.7, "comparisonAverage": 18.4, "change": 2.3,
                 "changePercent": 12.5}, {"metric": "co2", "unit": "ppm", "average": 687.08}],
    "anomalies": {"byStatus": {"confirmed": 2}, "total": 2, "items": [
        {"deviceId": "paris-01-ruuvi5-001", "classification": "sensor_fault", "confidencePercent": 98,
         "startedAt": "2026-10-30T11:57:15Z"}]},
}  # fmt: skip
TEXT = "Paris: 9 of 10 devices reporting; paris-01-ruuvi3-001 silent since 03:10 UTC."


def response(text=TEXT):
    return {
        "id": "resp_1", "object": "response", "created_at": 0, "model": "gpt-6-luna", "status": "completed",
        "output": [{"type": "message", "id": "m", "role": "assistant", "status": "completed",
                    "content": [{"type": "output_text", "text": text, "annotations": []}]}],
        "usage": {"input_tokens": 900, "output_tokens": 120, "total_tokens": 1020},
    }  # fmt: skip


def briefer(handler, requests):
    def record(request):
        requests.append(request)
        return handler(request)

    client = httpx.Client(transport=httpx.MockTransport(record))
    return Briefer("https://ais.openai.example/", "gpt-6-luna", token=lambda: "token", http_client=client)


def test_prompt_holds_the_figures_only():
    requests = []
    brief = briefer(lambda _: httpx.Response(200, json=response()), requests).write(FIGURES)
    assert brief is not None
    assert (brief.text, brief.model, brief.input_tokens, brief.output_tokens) == (TEXT, "gpt-6-luna", 900, 120)
    body = json.loads(requests[-1].content)
    assert body["instructions"] == INSTRUCTIONS
    assert json.loads(body["input"]) == FIGURES
    assert (body["store"], body["reasoning"]) == (False, {"effort": "low"})


@pytest.mark.parametrize(
    "failure", [httpx.Response(400, json={"error": {"message": "bad"}}), httpx.Response(200, json=response(""))]
)
def test_failure_leaves_no_brief(failure):
    assert briefer(lambda _: failure, []).write(FIGURES) is None


def test_grounded_text_has_no_unsupported_number():
    text = (
        "Paris: 9 of 10 devices reporting; paris-01-ruuvi3-001 has been silent since 03:10 UTC. "
        "Temperature averaged 20.7 °C, 2.3 °C (12.5 %) above the previous 7 days (18.4 °C); CO2 averaged 687.08 ppm. "
        "Two anomalies: a sensor fault on paris-01-ruuvi5-001 at 11:57 (98 %). Over the last 24 hours, about 21 °C."
    )
    assert unsupported_numbers(text, FIGURES) == []


def test_invented_numbers_are_reported():
    text = "Temperature averaged 22.4 °C; 4 devices were silent; humidity rose by -5 %."
    assert unsupported_numbers(text, FIGURES) == ["22.4", "4", "-5"]
