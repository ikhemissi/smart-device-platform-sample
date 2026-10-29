"""Candidate detection (M7 design D2): statistics select, the decision model decides.

For each device and metric, the *usual value* at a UTC hour is the mean of the device's hourly averages at that hour
over the previous days, and the *spread* is a robust measure (1.4826 x median absolute deviation) of how far hourly
averages stray from their usual value, floored per metric. The readings of the recent window are then compared with
it: far from usual (deviation), stuck on one value while the metric usually varies at that hour (flat), or a step
between consecutive readings much larger than the spread (jump).
"""

from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from itertools import pairwise
from statistics import fmean, median

MAD_SCALE = 1.4826
MIN_BASELINE_DAYS = 3
MIN_FLAT_READINGS = 10
EPISODE_GAP = timedelta(minutes=15)
# Smallest spread per metric, so that very stable devices are not over-sensitive (unit of the metric).
SPREAD_FLOORS = {"temperature": 0.2, "humidity": 1.0, "pressure": 0.5, "co2": 20.0, "illuminance": 10.0}
DEFAULT_SPREAD_FLOOR = 1e-3


@dataclass(frozen=True)
class HourlyRollup:
    device_id: str
    metric: str
    hour: datetime
    average: float
    min_value: float
    max_value: float


@dataclass(frozen=True)
class Reading:
    device_id: str
    metric: str
    measured_at: datetime
    value: float


@dataclass(frozen=True)
class Baseline:
    usual_by_hour: dict[int, float]
    varies_by_hour: dict[int, bool]
    spread: float
    days: int
    # Typical range within each UTC hour (medians of the daily hourly minimum and maximum).
    range_by_hour: dict[int, tuple[float, float]] = field(default_factory=dict)

    def usual(self, at: datetime) -> float | None:
        """Interpolated between the averages of the surrounding hours (taken at mid-hour), so that a daily slope does
        not look like a deviation at the end of each hour."""
        position = at.hour + at.minute / 60 + at.second / 3600 - 0.5
        before = int(position // 1) % 24
        after = (before + 1) % 24
        u0, u1 = self.usual_by_hour.get(before), self.usual_by_hour.get(after)
        if u0 is None or u1 is None:
            return self.usual_by_hour.get(at.hour)
        fraction = position - (position // 1)
        return u0 + (u1 - u0) * fraction


@dataclass(frozen=True)
class Assessment:
    """The window of one device and metric compared with its baseline (candidate or not)."""

    device_id: str
    metric: str
    started_at: datetime
    ended_at: datetime
    count: int
    observed_min: float
    observed_max: float
    observed_mean: float
    observed_last: float
    usual_value: float
    spread: float
    score: float
    max_step: float
    flat: bool
    usual_min: float | None = None
    usual_max: float | None = None


@dataclass(frozen=True)
class Candidate:
    kind: str  # deviation | flat | jump
    assessment: Assessment

    @property
    def score(self) -> float:
        a = self.assessment
        return a.max_step / a.spread if self.kind == "jump" else a.score


def build_baselines(rollups: Iterable[HourlyRollup]) -> dict[tuple[str, str], Baseline]:
    by_series: dict[tuple[str, str], list[HourlyRollup]] = defaultdict(list)
    for r in rollups:
        by_series[(r.device_id, r.metric)].append(r)
    baselines = {}
    for key, rows in by_series.items():
        days = len({r.hour for r in rows}) // 24  # full days of hourly rollups, wherever they start
        if days < MIN_BASELINE_DAYS:
            continue
        by_hour: dict[int, list[HourlyRollup]] = defaultdict(list)
        for r in rows:
            by_hour[r.hour.hour].append(r)
        usual = {h: fmean(r.average for r in hour_rows) for h, hour_rows in by_hour.items()}
        varies = {h: median(r.max_value - r.min_value for r in hour_rows) > 0 for h, hour_rows in by_hour.items()}
        ranges = {
            h: (median(r.min_value for r in hour_rows), median(r.max_value for r in hour_rows))
            for h, hour_rows in by_hour.items()
        }
        mad = median(abs(r.average - usual[r.hour.hour]) for r in rows)
        floor = SPREAD_FLOORS.get(key[1], DEFAULT_SPREAD_FLOOR)
        baselines[key] = Baseline(usual, varies, max(MAD_SCALE * mad, floor), days, ranges)
    return baselines


def assess(readings: Sequence[Reading], baseline: Baseline) -> Assessment | None:
    """Compares the window's readings (one device and metric) with the baseline; None without a usual value."""
    readings = sorted(readings, key=lambda r: r.measured_at)
    usual = [baseline.usual(r.measured_at) for r in readings]
    if not readings or any(u is None for u in usual):
        return None
    values = [r.value for r in readings]
    residual = fmean(v - u for v, u in zip(values, usual, strict=True))
    steps = [abs(b - a) for a, b in pairwise(values)]
    first, last = readings[0], readings[-1]
    ranges = [baseline.range_by_hour[h] for h in {r.measured_at.hour for r in readings} if h in baseline.range_by_hour]
    return Assessment(
        device_id=first.device_id,
        metric=first.metric,
        started_at=first.measured_at,
        ended_at=last.measured_at,
        count=len(values),
        observed_min=min(values),
        observed_max=max(values),
        observed_mean=fmean(values),
        observed_last=values[-1],
        usual_value=fmean(usual),
        spread=baseline.spread,
        score=residual / baseline.spread,
        max_step=max(steps, default=0.0),
        flat=len(values) >= MIN_FLAT_READINGS
        and min(values) == max(values)
        and all(baseline.varies_by_hour.get(r.measured_at.hour, False) for r in readings),
        usual_min=min((lo for lo, _ in ranges), default=None),
        usual_max=max((hi for _, hi in ranges), default=None),
    )


def classify_kind(assessment: Assessment, deviation: float) -> str | None:
    """The kind of candidate, if any: a stuck sensor first, then a sustained deviation, then an isolated jump.
    Readings that all stay within the device's usual range for the hour are not unusual, whatever the score: sharp
    daily transitions (light switching on at sunrise) make the hourly average a poor reference there."""
    if assessment.flat:
        return "flat"
    if within_usual_range(assessment):
        return None
    if abs(assessment.score) >= deviation:
        return "deviation"
    if assessment.max_step >= deviation * assessment.spread:
        return "jump"
    return None


def within_usual_range(assessment: Assessment) -> bool:
    lo, hi = assessment.usual_min, assessment.usual_max
    return lo is not None and hi is not None and lo <= assessment.observed_min and assessment.observed_max <= hi


def _unusual_part(kind: str, readings: Sequence[Reading], baseline: Baseline, deviation: float) -> Assessment | None:
    """A deviation or jump starts at the first reading beyond the deviation (or the first big step), not at the start
    of the window: the period and observed values then describe the unusual readings only."""
    if kind == "flat":
        return None
    readings = sorted(readings, key=lambda r: r.measured_at)
    limit = deviation * baseline.spread
    for i, r in enumerate(readings):
        usual = baseline.usual(r.measured_at)
        stepped = i > 0 and abs(r.value - readings[i - 1].value) >= limit
        if (usual is not None and abs(r.value - usual) >= limit) or stepped:
            return assess(readings[i:], baseline) if i > 0 else None
    return None


def detect(
    readings: Iterable[Reading], baselines: dict[tuple[str, str], Baseline], deviation: float
) -> tuple[list[Assessment], list[Candidate]]:
    """All assessments of the window (also used as neighbour context) and the candidates among them."""
    by_series: dict[tuple[str, str], list[Reading]] = defaultdict(list)
    for r in readings:
        by_series[(r.device_id, r.metric)].append(r)
    assessments, candidates = [], []
    for key in sorted(by_series):
        baseline = baselines.get(key)
        assessment = assess(by_series[key], baseline) if baseline else None
        if assessment is None:
            continue
        assessments.append(assessment)
        if kind := classify_kind(assessment, deviation):
            candidates.append(Candidate(kind, _unusual_part(kind, by_series[key], baseline, deviation) or assessment))
    return assessments, candidates


def continues(episode_ended_at: datetime, candidate: Candidate) -> bool:
    """A candidate extends an anomaly of the same device and metric that ended less than 15 minutes before it."""
    return candidate.assessment.started_at - episode_ended_at < EPISODE_GAP
