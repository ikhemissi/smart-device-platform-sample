import math
import random
from collections import defaultdict
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from statistics import fmean

import pytest

from anomaly_detector.detection import (
    Assessment,
    Candidate,
    HourlyRollup,
    Reading,
    build_baselines,
    continues,
    detect,
)

DEVICE = "paris-01-ruuvi5-001"
NOW = datetime(2026, 10, 29, 15, 0, tzinfo=UTC)
STEP = timedelta(seconds=30)
WINDOW = timedelta(minutes=15)


def temperature(at: datetime, rng: random.Random) -> float:
    """Simulator-like daily cycle: 21 °C +/- 3 °C, warmest at 15:00 UTC, small noise."""
    hours = at.hour + at.minute / 60 + at.second / 3600
    return 21 + 3 * math.sin(2 * math.pi * (hours - 9) / 24) + rng.gauss(0, 0.05)


def illuminance(at: datetime, rng: random.Random) -> float:
    """Daylight between 07:00 and 19:00 UTC, zero at night."""
    hours = at.hour + at.minute / 60
    return max(0.0, 400 * math.sin(math.pi * (hours - 7) / 12)) + (rng.gauss(0, 2) if 7 < hours < 19 else 0)


def series(metric, signal, start, end, rng, device=DEVICE):
    t, out = start, []
    while t < end:
        out.append(Reading(device, metric, t, round(signal(t, rng), 3)))
        t += STEP
    return out


def rollups(readings):
    by_hour = defaultdict(list)
    for r in readings:
        by_hour[(r.device_id, r.metric, r.measured_at.replace(minute=0, second=0, microsecond=0))].append(r.value)
    return [HourlyRollup(d, m, h, fmean(v), min(v), max(v)) for (d, m, h), v in by_hour.items()]


def scenario(now=NOW, metric="temperature", signal=temperature, days=7, seed=7):
    rng = random.Random(seed)
    history = series(metric, signal, now - timedelta(days=days), now - WINDOW, rng)
    window = series(metric, signal, now - WINDOW, now, rng)
    return build_baselines(rollups(history)), window


@pytest.mark.parametrize("hour", range(0, 24, 3))
def test_daily_cycle_is_not_flagged(hour):
    baselines, window = scenario(now=NOW.replace(hour=hour, minute=7))
    assessments, candidates = detect(window, baselines, deviation=4)
    assert len(assessments) == 1
    assert candidates == []
    assert abs(assessments[0].score) < 2


def test_ten_degree_rise_is_flagged():
    baselines, window = scenario()
    # The last 5 minutes are 10 °C above the usual level.
    warm = [
        Reading(r.device_id, r.metric, r.measured_at, r.value + 10)
        if r.measured_at >= NOW - timedelta(minutes=5)
        else r
        for r in window
    ]
    _, candidates = detect(warm, baselines, deviation=4)
    assert [c.kind for c in candidates] == ["deviation"]
    a = candidates[0].assessment
    assert a.observed_max > a.usual_value + 9
    assert a.score > 10
    assert a.started_at == window[0].measured_at and a.ended_at == window[-1].measured_at


def test_flat_line_is_flagged():
    baselines, window = scenario()
    stuck = [Reading(r.device_id, r.metric, r.measured_at, 23.7) for r in window]
    _, candidates = detect(stuck, baselines, deviation=4)
    assert [c.kind for c in candidates] == ["flat"]


def test_night_without_light_is_not_flat():
    baselines, window = scenario(now=NOW.replace(hour=2), metric="illuminance", signal=illuminance)
    assert {r.value for r in window} == {0.0}
    _, candidates = detect(window, baselines, deviation=4)
    assert candidates == []


def test_sunrise_within_the_usual_range_is_not_flagged():
    """Light switches on a few minutes late: far from the hourly average, but within what the hour usually shows."""
    baselines, window = scenario(now=NOW.replace(hour=7, minute=10), metric="illuminance", signal=illuminance)
    late = [replace(r, value=0.0) if r.measured_at.minute < 5 else r for r in window]
    assessments, candidates = detect(late, baselines, deviation=0.5)
    assert abs(assessments[0].score) >= 0.5
    assert candidates == []


def test_isolated_jump_is_flagged():
    baselines, window = scenario()
    spike = [Reading(r.device_id, r.metric, r.measured_at, r.value + 2) if i == 10 else r for i, r in enumerate(window)]
    _, candidates = detect(spike, baselines, deviation=4)
    assert [c.kind for c in candidates] == ["jump"]
    assert candidates[0].score >= 4


def test_device_with_little_history_is_skipped():
    baselines, window = scenario(days=2)
    assert baselines == {}
    assert detect(window, baselines, deviation=4) == ([], [])


def test_usual_range_within_the_hour():
    baselines, window = scenario()
    [assessment], _ = detect(window, baselines, deviation=4)
    assert assessment.usual_min < assessment.usual_value < assessment.usual_max
    assert assessment.usual_max - assessment.usual_min < 1


def test_spread_is_floored():
    baselines, _ = scenario()
    assert baselines[(DEVICE, "temperature")].spread == pytest.approx(0.2)


def _candidate(started_at):
    a = Assessment(DEVICE, "temperature", started_at, started_at + WINDOW, 30, 30, 35, 33, 35, 21, 0.2, 60, 10, False)
    return Candidate("deviation", a)


def test_episode_is_extended_when_the_previous_one_just_ended():
    assert continues(NOW - timedelta(minutes=5), _candidate(NOW - WINDOW))
    assert continues(NOW - timedelta(minutes=25), _candidate(NOW - WINDOW))


def test_new_episode_after_a_quiet_period():
    assert not continues(NOW - timedelta(minutes=31), _candidate(NOW - WINDOW))
