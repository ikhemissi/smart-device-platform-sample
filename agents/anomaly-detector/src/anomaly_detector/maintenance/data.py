"""Plain rows behind the maintenance assessments (role sdp_insights, M9 design D2 and D3). Aggregation stays in SQL:
a device reporting every 30 seconds has about 23,000 messages in 8 days."""

from datetime import datetime, timedelta
from typing import Any

import psycopg

from .battery import HISTORY, METRICS
from .signals import PREVIOUS, RECENT, MaintenanceDevice


def sites(conn: psycopg.Connection, configured: list[str]) -> list[str]:
    """The assessed sites: the configured ones that exist, or every site."""
    known = [r["site_id"] for r in conn.execute("SELECT site_id FROM sdp.sites ORDER BY site_id").fetchall()]
    return [s for s in known if s in configured] if configured else known


def devices(conn: psycopg.Connection, sites: list[str]) -> list[MaintenanceDevice]:
    rows = conn.execute(
        """SELECT device_id, site_id, display_name, last_payload_format AS payload_format, last_seen_at
             FROM sdp.devices WHERE site_id = ANY(%s) ORDER BY device_id""",
        (sites,),
    ).fetchall()
    return [MaintenanceDevice(**r) for r in rows]


def battery_points(conn: psycopg.Connection, sites: list[str], now: datetime) -> list[dict[str, Any]]:
    """Hourly battery averages of the last 14 days (rollups, at the middle of the hour), plus the raw readings of the
    hours not rolled up yet, averaged per hour."""
    since = now - HISTORY
    rolled = conn.execute(
        """SELECT device_id, metric, hour + interval '30 minutes' AS at, sum_value / sample_count AS average
             FROM sdp.metric_rollups_hourly
            WHERE site_id = ANY(%s) AND metric = ANY(%s) AND hour >= %s AND hour < %s AND sample_count > 0""",
        (sites, list(METRICS), since, now),
    ).fetchall()
    # The raw readings start at the hour after the last rolled-up one (same approach as the digest).
    last = max((r["at"] for r in rolled), default=None)
    raw_from = last.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1) if last else since
    recent = conn.execute(
        """SELECT device_id, m.key AS metric, to_timestamp(avg(extract(epoch FROM measured_at))) AS at,
                  avg((m.value)::float8) AS average
             FROM sdp.measurements, jsonb_each(metrics) AS m
            WHERE site_id = ANY(%s) AND measured_at >= %s AND measured_at <= %s AND m.key = ANY(%s)
              AND jsonb_typeof(m.value) = 'number'
            GROUP BY device_id, m.key, date_trunc('hour', measured_at)""",
        (sites, raw_from, now, list(METRICS)),
    ).fetchall()
    return [*rolled, *recent]


def message_periods(conn: psycopg.Connection, sites: list[str], now: datetime) -> list[dict[str, Any]]:
    """Per device, the messages of the last 24 hours (`recent`) and of the 7 days before: count, median interval and
    longest gap between consecutive messages, in seconds."""
    return conn.execute(
        """WITH m AS (
               SELECT device_id, measured_at,
                      extract(epoch FROM measured_at - lag(measured_at)
                                         OVER (PARTITION BY device_id ORDER BY measured_at)) AS gap
                 FROM sdp.measurements
                WHERE site_id = ANY(%(sites)s) AND measured_at > %(from)s AND measured_at <= %(now)s)
           SELECT device_id, measured_at > %(recent)s AS recent, count(*) AS messages,
                  percentile_cont(0.5) WITHIN GROUP (ORDER BY gap)::float8 AS median_interval_s,
                  max(gap)::float8 AS longest_gap_s
             FROM m GROUP BY 1, 2""",
        {"sites": sites, "from": now - RECENT - PREVIOUS, "recent": now - RECENT, "now": now},
    ).fetchall()


def rejected(conn: psycopg.Connection, device_ids: list[str], now: datetime) -> dict[str, int]:
    rows = conn.execute(
        """SELECT device_id, count(*) AS n FROM sdp.rejected_messages
            WHERE device_id = ANY(%s) AND received_at > %s AND received_at <= %s GROUP BY device_id""",
        (device_ids, now - RECENT, now),
    ).fetchall()
    return {r["device_id"]: r["n"] for r in rows}
