"""PostgreSQL access with the workload identity (ADR-0005): an Entra token is the password; no stored secret."""

from dataclasses import dataclass

import psycopg
from azure.core.credentials import TokenCredential
from psycopg.rows import dict_row

from .config import Settings

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
