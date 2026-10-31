"""Battery forecast (M9 design D2), pure functions: the level now, the trend per day and the days left before the
cut-off, from a device's hourly battery averages, starting after the last sudden change (a battery replacement or a
model change) and weighting the recent days more."""

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta

VOLTAGE = "battery_voltage"
LEVEL = "battery_level"
METRICS = (VOLTAGE, LEVEL)


@dataclass(frozen=True)
class Kind:
    unit: str
    cutoff: float
    # A step between consecutive hourly averages larger than this starts a new segment (either direction).
    sudden_change: float
    # Declines slower than this are no trend: no days left.
    min_decline_per_day: float
    decimals: int


KINDS = {
    VOLTAGE: Kind(unit="V", cutoff=2.5, sudden_change=0.1, min_decline_per_day=0.001, decimals=3),
    LEVEL: Kind(unit="%", cutoff=10.0, sudden_change=5.0, min_decline_per_day=0.05, decimals=1),
}
HALF_LIFE_DAYS = 3.0
# 12 hours of an aging battery (0.035 V a day) already give a clear slope over the hourly averages' noise.
MIN_TREND_SPAN = timedelta(hours=12)
HISTORY = timedelta(days=14)


@dataclass(frozen=True)
class HourlyBattery:
    """The average battery value of one hour (`at`: the hour's middle, or the time of a partial hour's readings)."""

    at: datetime
    average: float


@dataclass(frozen=True)
class Forecast:
    metric: str
    unit: str
    level: float
    slope_per_day: float | None
    days_left: float | None
    since: datetime
    cutoff: float

    def as_json(self) -> dict:
        return {
            "metric": self.metric,
            "unit": self.unit,
            "level": self.level,
            "slope_per_day": self.slope_per_day,
            "days_left": self.days_left,
            "since": self.since.isoformat(),
            "cutoff": self.cutoff,
        }


def segment(points: Sequence[HourlyBattery], metric: str) -> list[HourlyBattery]:
    """The points after the last sudden change (all of them when there is none). `points` are in time order."""
    threshold = KINDS[metric].sudden_change
    start = 0
    for i in range(1, len(points)):
        if abs(points[i].average - points[i - 1].average) > threshold:
            start = i
    return list(points[start:])


def _weighted_fit(points: Sequence[HourlyBattery], now: datetime) -> tuple[float, float]:
    """Weighted least squares of value against time in days from `now` (weights halve every 3 days of age): the fitted
    value now and the slope per day."""
    xs = [(p.at - now) / timedelta(days=1) for p in points]
    ws = [0.5 ** (max(0.0, -x) / HALF_LIFE_DAYS) for x in xs]
    total = sum(ws)
    mean_x = sum(w * x for w, x in zip(ws, xs, strict=True)) / total
    mean_y = sum(w * p.average for w, p in zip(ws, points, strict=True)) / total
    sxx = sum(w * (x - mean_x) ** 2 for w, x in zip(ws, xs, strict=True))
    sxy = sum(w * (x - mean_x) * (p.average - mean_y) for w, x, p in zip(ws, xs, points, strict=True))
    slope = sxy / sxx if sxx > 0 else 0.0
    return mean_y - slope * mean_x, slope


def forecast(points: Sequence[HourlyBattery], metric: str, now: datetime) -> Forecast | None:
    """None without readings. With less than 12 hours of readings since the last sudden change: the latest hourly
    average only, no trend nor days left."""
    if metric not in KINDS:
        raise ValueError(f"not a battery metric: {metric}")
    ordered = sorted((p for p in points if p.at <= now and p.at > now - HISTORY), key=lambda p: p.at)
    if not ordered:
        return None
    kind = KINDS[metric]
    current = segment(ordered, metric)
    since = current[0].at
    if current[-1].at - since < MIN_TREND_SPAN:
        return Forecast(metric, kind.unit, round(current[-1].average, kind.decimals), None, None, since, kind.cutoff)
    level, slope = _weighted_fit(current, now)
    days_left = None
    if slope < -kind.min_decline_per_day:
        days_left = round(max(0.0, (level - kind.cutoff) / -slope), 1)
    return Forecast(metric, kind.unit, round(level, kind.decimals), round(slope, kind.decimals + 2), days_left, since,
                    kind.cutoff)  # fmt: skip
