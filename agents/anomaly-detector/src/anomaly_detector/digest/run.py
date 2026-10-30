"""One digest run (M8 design D2, D10): the scheduled digests that are due, the requested ones, then the briefs that a
model failure left missing. The same logic runs against PostgreSQL and, in tests, against fakes."""

import logging
from dataclasses import dataclass, field
from datetime import datetime, time, timedelta
from typing import Any, Protocol

from opentelemetry.trace import SpanKind, Status, StatusCode

from ..telemetry import tracer
from .brief import Brief
from .figures import Window

log = logging.getLogger(__name__)

DAILY_AT = time(6, 0)


@dataclass(frozen=True)
class Request:
    id: int
    site_id: str
    requested_by: str


@dataclass(frozen=True)
class PendingBrief:
    digest_id: int
    site_id: str
    figures: dict[str, Any]


class Source(Protocol):
    def sites(self) -> list[str]: ...
    def figures(self, site_id: str, window: Window) -> dict[str, Any]: ...


class Store(Protocol):
    def scheduled_since(self, since: datetime) -> set[str]: ...
    def pending_requests(self) -> list[Request]: ...
    def save(
        self, site_id: str, trigger: str, window: Window, figures: dict[str, Any], brief: Brief | None,
        request: Request | None,
    ) -> int: ...  # fmt: skip
    def close_request(self, request: Request) -> None: ...
    def missing_briefs(self, since: datetime) -> list[PendingBrief]: ...
    def add_brief(self, digest_id: int, brief: Brief) -> None: ...


class Writes(Protocol):
    def write(self, figures: dict[str, Any]) -> Brief | None: ...


@dataclass
class RunResult:
    sites: int = 0
    scheduled: int = 0
    requested: int = 0
    briefs_added: int = 0
    errors: int = 0
    generated: list[int] = field(default_factory=list)


def daily_due(now: datetime) -> datetime | None:
    """The time today's scheduled digests became due, or None before 06:00 UTC."""
    due = datetime.combine(now.date(), DAILY_AT, tzinfo=now.tzinfo)
    return due if now >= due else None


@dataclass
class DigestRun:
    source: Source
    store: Store
    briefer: Writes

    def run(self, now: datetime) -> RunResult:
        result = RunResult()
        sites = self.source.sites()
        result.sites = len(sites)
        due = daily_due(now)
        if due is not None:
            done = self.store.scheduled_since(due)
            for site_id in sites:
                if site_id not in done:
                    self._generate(site_id, "scheduled", None, now, result)
                    result.scheduled += 1
        for request in self.store.pending_requests():
            if request.site_id in sites:
                self._generate(request.site_id, "requested", request, now, result)
                result.requested += 1
            else:
                log.warning("Request %s for %s ignored: the site has no digests", request.id, request.site_id)
                self.store.close_request(request)
        # Briefs a model failure left missing today (not the ones that just failed in this run).
        since = (
            due if due is not None else datetime.combine(now.date() - timedelta(days=1), DAILY_AT, tzinfo=now.tzinfo)
        )
        for pending in self.store.missing_briefs(since):
            if pending.digest_id in result.generated:
                continue
            brief = self.briefer.write(pending.figures)
            if brief is None:
                result.errors += 1
                continue
            self.store.add_brief(pending.digest_id, brief)
            result.briefs_added += 1
        return result

    def _generate(self, site_id: str, trigger: str, request: Request | None, now: datetime, result: RunResult) -> None:
        window = Window(now)
        with tracer.start_as_current_span("sdp.digest.figures") as span:
            span.set_attribute("sdp.digest.site", site_id)
            span.set_attribute("sdp.digest.trigger", trigger)
            figures = self.source.figures(site_id, window)
        brief = self.briefer.write(figures)
        if brief is None:
            result.errors += 1
        result.generated.append(self.store.save(site_id, trigger, window, figures, brief, request))


def traced_run(digest_run: DigestRun, now: datetime) -> RunResult:
    """A run as one Application Insights request (SERVER span) with its counts."""
    with tracer.start_as_current_span("sdp.digest.run", kind=SpanKind.SERVER) as span:
        try:
            result = digest_run.run(now)
        except Exception as error:
            span.record_exception(error)
            span.set_status(Status(StatusCode.ERROR, type(error).__name__))
            raise
        for name in ("sites", "scheduled", "requested", "briefs_added", "errors"):
            span.set_attribute(f"sdp.digest.{name}", getattr(result, name))
        if result.errors:
            span.set_status(Status(StatusCode.ERROR, f"{result.errors} brief(s) failed"))
        return result
