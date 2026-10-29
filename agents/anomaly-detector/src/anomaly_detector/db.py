"""PostgreSQL access with the workload identity (ADR-0005): an Entra token is the password; no stored secret."""

from dataclasses import dataclass
from datetime import datetime, timedelta

import psycopg
from azure.core.credentials import TokenCredential
from psycopg.rows import dict_row

from .config import Settings
from .detection import HourlyRollup, Reading

POSTGRES_SCOPE = "https://ossrdbms-aad.database.windows.net/.default"


def connect(settings: Settings, credential: TokenCredential) -> psycopg.Connection:
    token = credential.get_token(POSTGRES_SCOPE).token
    return psycopg.connect(
        host=settings.db_host,
        port=5432,
        dbname=settings.db_name,
        user=settings.db_user,
        password=token,
        sslmode="require",
        application_name="sdp-anomaly-detector",
        row_factory=dict_row,
        connect_timeout=15,
    )


@dataclass(frozen=True)
class Device:
    device_id: str
    site_id: str
    display_name: str | None
    payload_format: str


def analysed_devices(conn: psycopg.Connection, sites: list[str]) -> list[Device]:
    rows = conn.execute(
        """SELECT device_id, site_id, display_name, last_payload_format AS payload_format
             FROM sdp.devices WHERE site_id = ANY(%s) ORDER BY device_id""",
        (sites,),
    ).fetchall()
    return [Device(**row) for row in rows]


BASELINE_DAYS = 7
WINDOW = timedelta(minutes=15)


def baseline_rollups(
    conn: psycopg.Connection, sites: list[str], metrics: list[str], until: datetime
) -> list[HourlyRollup]:
    """Hourly rollups of the previous 7 days (design D2)."""
    rows = conn.execute(
        """SELECT device_id, metric, hour, sum_value / sample_count AS average, min_value, max_value
             FROM sdp.metric_rollups_hourly
            WHERE site_id = ANY(%s) AND metric = ANY(%s) AND hour >= %s AND hour < %s AND sample_count > 0""",
        (sites, metrics, until - timedelta(days=BASELINE_DAYS), until),
    ).fetchall()
    return [HourlyRollup(**row) for row in rows]


def window_readings(conn: psycopg.Connection, sites: list[str], metrics: list[str], until: datetime) -> list[Reading]:
    """Numeric readings of the analysed metrics in the 15 minutes before `until`."""
    rows = conn.execute(
        """SELECT device_id, m.key AS metric, measured_at, (m.value)::float8 AS value
             FROM sdp.measurements, jsonb_each(metrics) AS m
            WHERE site_id = ANY(%s) AND measured_at > %s AND measured_at <= %s
              AND m.key = ANY(%s) AND jsonb_typeof(m.value) = 'number'""",
        (sites, until - WINDOW, until, metrics),
    ).fetchall()
    return [Reading(**row) for row in rows]
