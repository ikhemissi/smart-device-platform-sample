import random
from datetime import UTC, datetime, timedelta

import pytest

from anomaly_detector.maintenance.battery import LEVEL, MIN_TREND_SPAN, VOLTAGE, HourlyBattery, forecast, segment

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


def test_steepening_starts_a_new_segment():
    # An aging battery: 0.0015 V/day for 13 days, then 0.035 V/day for the last day, without a step.
    slow = series(2.98, 0.0015, 13 * 24, end=NOW - timedelta(days=1))
    fast = series(slow[-1].average - 0.035, 0.035, 24 - 1)
    f = forecast(slow + fast, VOLTAGE, NOW)
    assert abs(f.since - fast[0].at) <= timedelta(hours=1)
    assert f.slope_per_day == pytest.approx(-0.035, abs=0.001)
    assert f.days_left == pytest.approx((fast[-1].average - 2.5) / 0.035, abs=0.3)


def test_small_step_over_two_hours_starts_a_new_segment():
    # A 0.06 V step down, under the sudden-change threshold and spread over two hourly averages.
    before = series(2.95, 0.0015, 10 * 24, end=NOW - timedelta(days=1, hours=2))
    middle = [HourlyBattery(NOW - timedelta(days=1, hours=1), before[-1].average - 0.026)]
    after = series(before[-1].average - 0.06, 0.0015, 24)
    f = forecast(before + middle + after, VOLTAGE, NOW)
    assert f.since >= middle[0].at
    assert f.level == pytest.approx(after[-1].average, abs=0.001)
    assert f.slope_per_day == pytest.approx(-0.0015, abs=0.0005)


def test_steady_noisy_decline_is_not_split():
    noise = random.Random(7)
    points = [HourlyBattery(p.at, p.average + noise.gauss(0, 0.0003)) for p in series(2.95, 0.0015, 14 * 24 - 1)]
    f = forecast(points, VOLTAGE, NOW)
    assert f.since == points[0].at
    assert f.slope_per_day == pytest.approx(-0.0015, abs=0.0003)


def test_whole_percent_level_staircase_is_not_split():
    points = [HourlyBattery(p.at, float(round(p.average))) for p in series(80.0, 0.5, 14 * 24 - 1)]
    f = forecast(points, LEVEL, NOW)
    assert f.since == points[0].at
    assert f.slope_per_day == pytest.approx(-0.5, abs=0.1)


def test_last_of_two_changes_wins():
    first = series(2.95, 0.0015, 6 * 24, end=NOW - timedelta(days=4))
    second = series(first[-1].average - 0.05, 0.0015, 3 * 24 - 1, end=NOW - timedelta(days=1))
    third = series(second[-1].average - 0.035, 0.035, 24 - 1)
    assert abs(segment(first + second + third, VOLTAGE)[0].at - third[0].at) <= timedelta(hours=1)


def test_a_change_point_leaves_at_least_12_hours():
    slow = series(2.98, 0.0015, 10 * 24, end=NOW - timedelta(hours=8))
    fast = series(slow[-1].average - 0.035 * 8 / 24, 0.035, 8 - 1)
    assert NOW - segment(slow + fast, VOLTAGE)[0].at >= MIN_TREND_SPAN


def test_level_step_over_two_hours_starts_a_new_segment():
    # A 2.7-point drop of a level spread over two hourly averages, under the 5-point sudden change.
    before = series(88.0, 0.2, 10 * 24, end=NOW - timedelta(days=1, hours=2))
    middle = [HourlyBattery(NOW - timedelta(days=1, hours=1), before[-1].average - 1.2)]
    after = series(before[-1].average - 2.7, 0.2, 24)
    f = forecast(before + middle + after, LEVEL, NOW)
    assert f.level == pytest.approx(after[-1].average, abs=0.1)
