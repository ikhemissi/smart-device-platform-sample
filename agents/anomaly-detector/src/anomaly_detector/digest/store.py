"""PostgreSQL source and store of the digest job (role sdp_insights, migration V9)."""

from dataclasses import dataclass
from datetime import datetime
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

from . import data
from .brief import Brief
from .config import DigestSettings
from .figures import Window, compute
from .run import PendingBrief, Request


@dataclass
class PgSource:
    conn: psycopg.Connection
    settings: DigestSettings

    def sites(self) -> list[str]:
        return data.sites(self.conn, self.settings.sites)

    def figures(self, site_id: str, window: Window) -> dict[str, Any]:
        return compute(data.load(self.conn, site_id, self.settings.metrics, window), window)


@dataclass
class PgStore:
    conn: psycopg.Connection

    def scheduled_since(self, since: datetime) -> set[str]:
        rows = self.conn.execute(
            "SELECT DISTINCT site_id FROM sdp.site_digests WHERE trigger = 'scheduled' AND generated_at >= %s", (since,)
        ).fetchall()
        return {r["site_id"] for r in rows}

    def pending_requests(self) -> list[Request]:
        rows = self.conn.execute(
            """SELECT id, site_id, requested_by FROM sdp.site_digest_requests
                WHERE processed_at IS NULL ORDER BY requested_at"""
        ).fetchall()
        return [Request(**r) for r in rows]

    def save(
        self,
        site_id: str,
        trigger: str,
        window: Window,
        figures: dict[str, Any],
        brief: Brief | None,
        request: Request | None,
    ) -> int:
        """The digest and, for a requested one, its request marked as served, in one transaction."""
        with self.conn.transaction():
            digest_id = self.conn.execute(
                """INSERT INTO sdp.site_digests (site_id, trigger, period_from, period_to, comparison_from,
                                                generated_at, figures, brief, brief_model, brief_generated_at,
                                                requested_by)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id""",
                (
                    site_id,
                    trigger,
                    window.period_from,
                    window.generated_at,
                    window.comparison_from,
                    window.generated_at,
                    Jsonb(figures),
                    brief.text if brief else None,
                    brief.model if brief else None,
                    window.generated_at if brief else None,
                    request.requested_by if request else None,
                ),
            ).fetchone()["id"]
            if request is not None:
                self.conn.execute(
                    "UPDATE sdp.site_digest_requests SET processed_at = now(), digest_id = %s WHERE id = %s",
                    (digest_id, request.id),
                )
        return digest_id

    def close_request(self, request: Request) -> None:
        self.conn.execute("UPDATE sdp.site_digest_requests SET processed_at = now() WHERE id = %s", (request.id,))

    def missing_briefs(self, since: datetime) -> list[PendingBrief]:
        rows = self.conn.execute(
            """SELECT id AS digest_id, site_id, figures FROM sdp.site_digests
                WHERE brief IS NULL AND generated_at >= %s ORDER BY generated_at""",
            (since,),
        ).fetchall()
        return [PendingBrief(**r) for r in rows]

    def add_brief(self, digest_id: int, brief: Brief) -> None:
        self.conn.execute(
            "UPDATE sdp.site_digests SET brief = %s, brief_model = %s, brief_generated_at = now() WHERE id = %s",
            (brief.text, brief.model, digest_id),
        )
