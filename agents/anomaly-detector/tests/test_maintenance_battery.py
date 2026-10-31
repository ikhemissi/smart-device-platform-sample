from datetime import UTC, datetime, timedelta

import pytest

from anomaly_detector.maintenance.battery import LEVEL, VOLTAGE, HourlyBattery, forecast, segment

NOW = datetime(2026, 10, 29, 12, 0, tzinfo=UTC)


def series(start_value, per_day, hours, end=NOW):
    """Hourly points ending at `end`, declining by `per_day`."""
    return [
        HourlyBattery(end - timedelta(hours=h), start_value - per_day * (hours - h) / 24) for h in range(hours, -1, -1)
    ]


def test_steady_decline_gives_the_expected_days_left():
    # 0.05 V/day for 5 days, ending at 2.65 V: 3 days before 2.5 V.
    points = series(2.90, 0.05, 5 * 24)
    f = forecast(points, VOLTAGE, NOW)
    assert f.level == pytest.approx(2.65, abs=0.001)
    assert f.slope_per_day == pytest.approx(-0.05, abs=1e-4)
    assert f.days_left == pytest.approx(3.0, abs=0.05)
    assert f.unit == "V" and f.cutoff == 2.5


def test_flat_battery_has_no_days_left():
    points = series(2.95, 0.0005, 10 * 24)
    f = forecast(points, VOLTAGE, NOW)
    assert f.slope_per_day is not None
    assert f.days_left is None


def test_replacement_starts_a_new_segment():
    old = series(2.60, 0.05, 4 * 24, end=NOW - timedelta(days=3))
    new = series(3.05, 0.001, 3 * 24 - 1)
    f = forecast(old + new, VOLTAGE, NOW)
    assert f.since == new[0].at
    assert f.level == pytest.approx(3.047, abs=0.002)
    assert f.days_left is None


def test_model_change_drop_starts_a_new_segment():
    before = series(3.0, 0.0, 3 * 24, end=NOW - timedelta(days=2))
    after = series(2.80, 0.05, 2 * 24 - 1)  # ends at 2.70 V: 4 days left
    assert segment(before + after, VOLTAGE)[0] == after[0]
    f = forecast(before + after, VOLTAGE, NOW)
    assert f.since == after[0].at
    assert f.days_left == pytest.approx(4.0, abs=0.1)


def test_recent_days_weigh_more():
    # Slow decline for 10 days, then fast for the last 2: the trend leans towards the recent slope.
    slow = series(2.95, 0.002, 10 * 24, end=NOW - timedelta(days=2))
    fast = series(slow[-1].average - 0.03 * 2, 0.03, 2 * 24 - 1)
    f = forecast(slow + fast, VOLTAGE, NOW)
    unweighted = (fast[-1].average - slow[0].average) / 12
    assert f.slope_per_day < unweighted
    assert f.slope_per_day < -0.008


def test_less_than_12_hours_gives_the_level_only():
    points = series(2.80, 0.05, 10)
    f = forecast(points, VOLTAGE, NOW)
    assert f.level == pytest.approx(points[-1].average, abs=0.001)
    assert f.slope_per_day is None and f.days_left is None


def test_twelve_hours_of_an_aging_battery_give_its_trend():
    """The demo's aging devices (0.035 V a day) are forecast half a day after a new battery or a model change."""
    f = forecast(series(2.66, 0.035, 12), VOLTAGE, NOW)
    assert f.slope_per_day == pytest.approx(-0.035, abs=0.002)
    assert f.days_left == pytest.approx((2.66 - 0.035 * 12 / 24 - 2.5) / 0.035, abs=0.3)


def test_level_cutoff_and_threshold():
    points = series(40.0, 5.0, 4 * 24)  # 20 % now, 5 %/day: 2 days before 10 %
    f = forecast(points, LEVEL, NOW)
    assert f.unit == "%" and f.cutoff == 10.0
    assert f.days_left == pytest.approx(2.0, abs=0.05)
    slow = forecast(series(50.0, 0.04, 4 * 24), LEVEL, NOW)
    assert slow.days_left is None


def test_level_step_of_more_than_five_points_is_a_sudden_change():
    points = [HourlyBattery(NOW - timedelta(hours=2), 30.0), HourlyBattery(NOW - timedelta(hours=1), 36.0)]
    assert segment(points, LEVEL) == points[1:]
    small = [HourlyBattery(NOW - timedelta(hours=2), 30.0), HourlyBattery(NOW - timedelta(hours=1), 34.0)]
    assert segment(small, LEVEL) == small
    volts = [HourlyBattery(NOW - timedelta(hours=2), 2.90), HourlyBattery(NOW - timedelta(hours=1), 2.95)]
    assert segment(volts, VOLTAGE) == volts


def test_below_the_cutoff_has_zero_days_left():
    f = forecast(series(2.6, 0.05, 4 * 24), VOLTAGE, NOW)
    assert f.days_left == 0.0


def test_no_readings_and_old_readings():
    assert forecast([], VOLTAGE, NOW) is None
    assert forecast([HourlyBattery(NOW - timedelta(days=20), 2.9)], VOLTAGE, NOW) is None
    with pytest.raises(ValueError):
        forecast([], "temperature", NOW)
