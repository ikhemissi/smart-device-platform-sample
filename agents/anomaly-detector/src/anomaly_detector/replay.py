"""Dry runs (M7 tasks 2.5/2.6): replay detection cycles every 5 minutes over a past period with the live logic, an
in-memory store and no write. Without a classifier no model is called; with one, new episodes are classified (still
nothing written) so that the confidence can be tuned from the recorded probabilities.
"""

import bisect
import json
import logging
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta
from typing import IO

import psycopg

from . import db
from .config import Settings
from .cycle import Anomaly, Detector
from .db import Device
from .decision import Classifier
from .detection import Baseline, Candidate, HourlyRollup, Reading, build_baselines

CYCLE = timedelta(minutes=5)
# Hourly rollups trail the clock (the maintenance job rolls up hours that ended before the ingestion watermark).
ROLLUP_LAG = timedelta(hours=1)

log = logging.getLogger(__name__)


@dataclass
class ReplaySource:
    """The data of a whole period loaded once; each cycle sees what the live job would have seen at its time."""

    device_list: list[Device]
    unit_map: dict[str, str]
    rollups: list[HourlyRollup]
    window_by_time: list[Reading]
    _times: list[datetime] = field(init=False)
    _baselines: dict[datetime, dict[tuple[str, str], Baseline]] = field(default_factory=dict, init=False)

    def __post_init__(self) -> None:
        self.window_by_time.sort(key=lambda r: r.measured_at)
        self._times = [r.measured_at for r in self.window_by_time]

    @classmethod
    def load(cls, conn: psycopg.Connection, settings: Settings, start: datetime, end: datetime) -> "ReplaySource":
        return cls(
            db.analysed_devices(conn, settings.sites),
            db.metric_units(conn),
            db.baseline_rollups(conn, settings.sites, settings.metrics, end, days=(end - start).days + 8),
            db.window_readings(conn, settings.sites, settings.metrics, end, window=end - start + db.WINDOW),
        )

    def devices(self) -> list[Device]:
        return self.device_list

    def units(self) -> dict[str, str]:
        return self.unit_map

    def baselines(self, until: datetime) -> dict[tuple[str, str], Baseline]:
        cutoff = until.replace(minute=0, second=0, microsecond=0) - ROLLUP_LAG
        if cutoff not in self._baselines:
            since = cutoff - timedelta(days=db.BASELINE_DAYS)
            self._baselines[cutoff] = build_baselines(r for r in self.rollups if since <= r.hour < cutoff)
        return self._baselines[cutoff]

    def readings(self, until: datetime) -> list[Reading]:
        lo = bisect.bisect_right(self._times, until - db.WINDOW)
        hi = bisect.bisect_right(self._times, until)
        return self.window_by_time[lo:hi]


@dataclass
class MemoryStore:
    """Episodes kept in memory; an extension merges extremes (the mean is the latest window's, enough for counts)."""

    anomalies: list[Anomaly] = field(default_factory=list)

    def recent(self, since: datetime) -> list[Anomaly]:
        return [a for a in self.anomalies if a.ended_at >= since]

    def add(self, anomaly: Anomaly) -> Anomaly:
        anomaly.id = len(self.anomalies) + 1
        self.anomalies.append(anomaly)
        return anomaly

    def extend(self, anomaly: Anomaly, candidate: Candidate) -> None:
        a = candidate.assessment
        anomaly.ended_at = max(anomaly.ended_at, a.ended_at)
        anomaly.observed_min = min(anomaly.observed_min, a.observed_min)
        anomaly.observed_max = max(anomaly.observed_max, a.observed_max)
        anomaly.observed_mean, anomaly.observed_last = a.observed_mean, a.observed_last
        if abs(candidate.score) > abs(anomaly.score):
            anomaly.score = candidate.score

    def unfinished(self, limit: int) -> list[Anomaly]:
        return [a for a in self.anomalies if a.decision == "pending"][:limit]

    def record_decision(self, anomaly: Anomaly) -> None:
        pass

    def record_explanation(self, anomaly: Anomaly) -> None:
        pass


def dry_run(
    settings: Settings,
    source: ReplaySource,
    start: datetime,
    end: datetime,
    classifier: Classifier | None = None,
    output: IO[str] | None = None,
) -> dict[str, Counter]:
    """Replays the cycles of [start, end) and returns per-day counts; episodes are written to `output` (JSON lines)."""
    store = MemoryStore()
    detector = Detector(settings, source, store, classifier=classifier)
    per_day: dict[str, Counter] = defaultdict(Counter)
    at = start
    while at < end:
        result = detector.run(at)
        day = at.date().isoformat()
        per_day[day]["candidates"] += result.candidates
        per_day[day]["episodes"] += result.created
        for kind, count in result.kinds.items():
            per_day[day][f"episodes.{kind}"] += count
        for status, count in result.decisions.items():
            per_day[day][f"decisions.{status}"] += count
        at += CYCLE
    if output is not None:
        for anomaly in store.anomalies:
            output.write(json.dumps(asdict(anomaly), default=str, ensure_ascii=False) + "\n")
    return dict(per_day)
