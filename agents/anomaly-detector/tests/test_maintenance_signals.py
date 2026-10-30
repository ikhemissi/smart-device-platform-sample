from datetime import UTC, datetime, timedelta

import pytest

from anomaly_detector.maintenance.signals import MaintenanceDevice, battery_series, device_forecast, signals

NOW = datetime(2026, 10, 29, 12, 0, tzinfo=UTC)


def device(device_id="paris-01-ruuvi5-003", last_seen=NOW - timedelta(seconds=20)):
    return MaintenanceDevice(device_id, "paris-01", "Cold room 3", "ruuvi-v5", last_seen)


def period(device_id, recent, messages, median, longest):
    return {
        "device_id": device_id,
        "recent": recent,
        "messages": messages,
        "median_interval_s": median,
        "longest_gap_s": longest,
    }


def test_growing_gaps_compared_with_the_previous_week():
    rows = [
        period("paris-01-ruuvi5-003", False, 20160, 30.0, 95.0),
        period("paris-01-ruuvi5-003", True, 1500, 30.0, 600.0),
        period("paris-01-ruuvi5-001", True, 2880, 30.0, 31.0),
    ]
    s = signals(device(), rows, {}, NOW)
    assert s.last_24h.messages == 1500
    assert s.previous_7d.median_interval_s == 30.0
    assert s.gap_ratio == pytest.approx(20.0)
    assert s.as_json()["longest_gap_over_usual_interval"] == 20.0
    assert s.minutes_since_last == pytest.approx(1 / 3)


def test_silent_device():
    s = signals(device(last_seen=NOW - timedelta(hours=3)), [period("paris-01-ruuvi5-003", False, 900, 30.0, 40.0)],
                {}, NOW)  # fmt: skip
    assert s.minutes_since_last == pytest.approx(180)
    assert s.last_24h.messages == 0
    assert s.gap_ratio is None
    assert s.as_json()["last_24h"] == {"messages": 0, "median_interval_s": None, "longest_gap_s": None}


def test_never_seen_device():
    s = signals(device(last_seen=None), [], {}, NOW)
    assert s.minutes_since_last is None
    assert s.as_json()["minutes_since_last"] is None


def test_rejected_messages():
    s = signals(device(), [], {"paris-01-ruuvi5-003": 7, "paris-01-ruuvi5-001": 2}, NOW)
    assert s.rejected_24h == 7
    assert signals(device("lyon-01-ruuvi3-001"), [], {"paris-01-ruuvi5-003": 7}, NOW).rejected_24h == 0


def battery_row(device_id, metric, hours_ago, average):
    return {"device_id": device_id, "metric": metric, "at": NOW - timedelta(hours=hours_ago), "average": average}


def test_battery_series_by_device_and_metric_in_time_order():
    rows = [
        battery_row("a", "battery_voltage", 1, 2.9),
        battery_row("a", "battery_voltage", 2, 2.91),
        battery_row("a", "battery_level", 1, 80.0),
        battery_row("b", "battery_voltage", 1, None),
        battery_row("b", "temperature", 1, 21.0),
    ]
    series = battery_series(rows)
    assert [p.average for p in series["a"]["battery_voltage"]] == [2.91, 2.9]
    assert series["a"]["battery_level"][0].average == 80.0
    assert "b" not in series


def test_device_without_battery_has_no_forecast():
    assert device_forecast(None, NOW) is None
    assert device_forecast({}, NOW) is None


def test_forecast_prefers_the_voltage_then_the_level():
    rows = [battery_row("a", "battery_voltage", h, 2.9) for h in range(48)]
    rows += [battery_row("a", "battery_level", h, 80.0) for h in range(48)]
    rows += [battery_row("b", "battery_level", h, 40.0 + h * 0.5) for h in range(48)]
    series = battery_series(rows)
    assert device_forecast(series["a"], NOW).metric == "battery_voltage"
    level = device_forecast(series["b"], NOW)
    assert level.metric == "battery_level"
    assert level.days_left == pytest.approx(30 / 12, abs=0.1)
