"""Rows behind a site's digest figures (role sdp_insights, migrations V8 and V9). Alerts are read without their
recipients and rejected messages without their payload."""

from datetime import datetime
from typing import Any

import psycopg

from .. import db
from .figures import DeviceRow, MetricRow, SiteData, Window


def sites(conn: psycopg.Connection, configured: list[str]) -> list[str]:
    """The sites that get a digest: the configured ones that exist, or every site."""
    known = [r["site_id"] for r in conn.execute("SELECT site_id FROM sdp.sites ORDER BY site_id").fetchall()]
    return [s for s in known if s in configured] if configured else known


def load(
    conn: psycopg.Connection, site_id: str, metrics: list[str], window: Window, maintenance: bool = False
) -> SiteData:
    """`maintenance`: the maintenance job assesses the site (its queue is part of the figures)."""
    site = conn.execute("SELECT display_name FROM sdp.sites WHERE site_id = %s", (site_id,)).fetchone()
    return SiteData(
        site_id=site_id,
        site_name=site["display_name"] if site else site_id,
        devices=_devices(conn, site_id),
        metrics=_metrics(conn, site_id, metrics, window),
        units=db.metric_units(conn),
        alerts=_alerts(conn, site_id, window),
        open_alerts=conn.execute(
            "SELECT count(*) AS n FROM sdp.alert_events WHERE site_id = %s AND status = 'OPEN'", (site_id,)
        ).fetchone()["n"],
        anomalies=_anomalies(conn, site_id, window),
        rejected=_rejected(conn, site_id, window),
        certificates=_certificates(conn, site_id),
        maintenance=_maintenance(conn, site_id) if maintenance else None,
    )


def _devices(conn: psycopg.Connection, site_id: str) -> list[DeviceRow]:
    rows = conn.execute(
        "SELECT device_id, display_name, last_seen_at FROM sdp.devices WHERE site_id = %s ORDER BY device_id",
        (site_id,),
    ).fetchall()
    return [DeviceRow(**r) for r in rows]


def _alerts(conn: psycopg.Connection, site_id: str, window: Window) -> list[dict[str, Any]]:
    rows = conn.execute(
        """SELECT device_id, metric, direction, status, opened_at, closed_at, worst_value FROM sdp.alert_events
            WHERE site_id = %s AND opened_at >= %s AND opened_at < %s ORDER BY opened_at DESC""",
        (site_id, window.period_from, window.generated_at),
    ).fetchall()
    keys = {"device_id": "deviceId", "opened_at": "openedAt", "closed_at": "closedAt", "worst_value": "worstValue"}
    return [{keys.get(k, k): v for k, v in r.items()} for r in rows]


def _anomalies(conn: psycopg.Connection, site_id: str, window: Window) -> list[dict[str, Any]]:
    rows = conn.execute(
        """SELECT device_id, metric, kind, classification, started_at, ended_at,
                  CASE WHEN reviewed_at IS NOT NULL THEN 'reviewed' ELSE decision END AS status,
                  (probabilities ->> classification)::float8 AS confidence
             FROM sdp.anomalies WHERE site_id = %s AND started_at < %s AND ended_at >= %s
            ORDER BY started_at DESC""",
        (site_id, window.generated_at, window.period_from),
    ).fetchall()
    keys = {"device_id": "deviceId", "started_at": "startedAt", "ended_at": "endedAt"}
    return [{keys.get(k, k): v for k, v in r.items()} for r in rows]


def _rejected(conn: psycopg.Connection, site_id: str, window: Window) -> dict[str, int]:
    rows = conn.execute(
        """SELECT reason, count(*) AS n FROM sdp.rejected_messages
            WHERE site_id = %s AND received_at >= %s AND received_at < %s GROUP BY reason""",
        (site_id, window.period_from, window.generated_at),
    ).fetchall()
    return {r["reason"]: r["n"] for r in rows}


def _certificates(conn: psycopg.Connection, site_id: str) -> list[dict[str, Any]]:
    rows = conn.execute(
        "SELECT slot, subject, not_after FROM sdp.site_certificates WHERE site_id = %s", (site_id,)
    ).fetchall()
    return [{"slot": r["slot"], "subject": r["subject"], "notAfter": r["not_after"]} for r in rows]


def _maintenance(conn: psycopg.Connection, site_id: str) -> list[dict[str, Any]]:
    """The site's queue at generation time (open and planned items), most likely first (migration V10)."""
    rows = conn.execute(
        """SELECT device_id, status, probability, (battery ->> 'days_left')::float8 AS days_left
             FROM sdp.maintenance_items WHERE site_id = %s AND status IN ('open', 'planned')
            ORDER BY probability DESC, device_id""",
        (site_id,),
    ).fetchall()
    return [
        {"deviceId": r["device_id"], "status": r["status"], "probability": r["probability"], "daysLeft": r["days_left"]}
        for r in rows
    ]


def _metrics(conn: psycopg.Connection, site_id: str, metrics: list[str], window: Window) -> list[MetricRow]:
    """Hourly rollups for both periods, plus the raw readings of the recent hours not rolled up yet."""
    at: datetime = window.generated_at
    rows = conn.execute(
        """SELECT metric, hour >= %(period_from)s AS in_period, count(DISTINCT device_id) AS devices,
                  sum(sum_value) AS total, sum(sample_count)::bigint AS count, min(min_value) AS min_value,
                  max(max_value) AS max_value
             FROM sdp.metric_rollups_hourly
            WHERE site_id = %(site)s AND metric = ANY(%(metrics)s) AND hour >= %(comparison_from)s AND hour < %(at)s
            GROUP BY 1, 2""",
        {
            "site": site_id,
            "metrics": metrics,
            "comparison_from": window.comparison_from,
            "period_from": window.period_from,
            "at": at,
        },
    ).fetchall()
    rolled = conn.execute(
        """SELECT max(hour) + interval '1 hour' AS until FROM sdp.metric_rollups_hourly
            WHERE site_id = %s AND hour >= %s""",
        (site_id, window.period_from),
    ).fetchone()["until"]
    recent = conn.execute(
        """SELECT m.key AS metric, true AS in_period, count(DISTINCT device_id) AS devices,
                  sum((m.value)::float8) AS total, count(*) AS count, min((m.value)::float8) AS min_value,
                  max((m.value)::float8) AS max_value
             FROM sdp.measurements, jsonb_each(metrics) AS m
            WHERE site_id = %s AND measured_at >= %s AND measured_at < %s AND m.key = ANY(%s)
              AND jsonb_typeof(m.value) = 'number'
            GROUP BY 1""",
        (site_id, rolled or window.period_from, at, metrics),
    ).fetchall()
    return [MetricRow(**r) for r in [*rows, *recent]]
