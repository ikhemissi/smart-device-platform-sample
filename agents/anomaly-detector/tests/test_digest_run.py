from datetime import UTC, datetime, timedelta

import pytest
from opentelemetry.trace import SpanKind

from anomaly_detector.digest.brief import Brief
from anomaly_detector.digest.run import DigestRun, PendingBrief, Request, daily_due, traced_run

from .tracing import EXPORTER

MORNING = datetime(2026, 10, 30, 6, 5, tzinfo=UTC)
NIGHT = datetime(2026, 10, 30, 5, 55, tzinfo=UTC)


class FakeSource:
    def __init__(self, sites=("lyon-01", "paris-01")):
        self.configured = list(sites)

    def sites(self):
        return self.configured

    def figures(self, site_id, window):
        return {"site": {"id": site_id}, "period": {"to": window.generated_at.isoformat()}}


class FakeStore:
    def __init__(self):
        self.digests = []  # (id, site, trigger, brief text, requested_by, generated_at)
        self.requests = []
        self.closed = []

    def scheduled_since(self, since):
        return {d["site"] for d in self.digests if d["trigger"] == "scheduled" and d["at"] >= since}

    def pending_requests(self):
        return [r for r in self.requests if r.id not in self.closed]

    def save(self, site_id, trigger, window, figures, brief, request):
        digest = {"id": len(self.digests) + 1, "site": site_id, "trigger": trigger, "figures": figures,
                  "brief": brief.text if brief else None, "by": request.requested_by if request else None,
                  "at": window.generated_at}  # fmt: skip
        self.digests.append(digest)
        if request:
            self.closed.append(request.id)
        return digest["id"]

    def close_request(self, request):
        self.closed.append(request.id)

    def missing_briefs(self, since):
        return [
            PendingBrief(d["id"], d["site"], d["figures"]) for d in self.digests if not d["brief"] and d["at"] >= since
        ]

    def add_brief(self, digest_id, brief):
        self.digests[digest_id - 1]["brief"] = brief.text


class FakeBriefer:
    def __init__(self, *failures):
        self.failures, self.calls = list(failures), 0

    def write(self, figures):
        self.calls += 1
        if self.failures and self.failures.pop(0):
            return None
        return Brief(f"Brief of {figures['site']['id']}.", "gpt-6-luna")


def test_daily_digests_are_due_from_six_utc():
    assert daily_due(NIGHT) is None
    assert daily_due(MORNING) == datetime(2026, 10, 30, 6, 0, tzinfo=UTC)


def test_each_site_gets_one_scheduled_digest_a_day():
    store = FakeStore()
    run = DigestRun(FakeSource(), store, FakeBriefer())
    assert run.run(NIGHT).scheduled == 0
    first = run.run(MORNING)
    assert (first.sites, first.scheduled, first.errors) == (2, 2, 0)
    assert [(d["site"], d["trigger"], d["brief"]) for d in store.digests] == [
        ("lyon-01", "scheduled", "Brief of lyon-01."),
        ("paris-01", "scheduled", "Brief of paris-01."),
    ]
    assert run.run(MORNING + timedelta(minutes=5)).scheduled == 0
    assert run.run(MORNING + timedelta(days=1)).scheduled == 2


def test_requests_are_served_at_any_time_and_closed():
    store = FakeStore()
    store.requests = [Request(1, "paris-01", "Erin Editor"), Request(2, "marseille-01", "Erin Editor")]
    result = DigestRun(FakeSource(), store, FakeBriefer()).run(NIGHT)
    assert result.requested == 1
    assert [(d["site"], d["trigger"], d["by"]) for d in store.digests] == [("paris-01", "requested", "Erin Editor")]
    assert store.closed == [1, 2]  # the request of a site without digests is closed without one


def test_a_failed_brief_is_kept_without_brief_then_added_by_a_later_run():
    store = FakeStore()
    briefer = FakeBriefer(True)  # the first call fails
    run = DigestRun(FakeSource(sites=["paris-01"]), store, briefer)
    first = run.run(MORNING)
    assert (first.scheduled, first.errors, first.briefs_added) == (1, 1, 0)
    assert store.digests[0]["brief"] is None
    assert briefer.calls == 1  # not retried in the same run
    second = run.run(MORNING + timedelta(minutes=5))
    assert (second.scheduled, second.briefs_added) == (0, 1)
    assert store.digests[0]["brief"] == "Brief of paris-01."


def test_a_run_is_one_request_span_with_its_counts():
    EXPORTER.clear()
    traced_run(lambda: DigestRun(FakeSource(sites=["paris-01"]), FakeStore(), FakeBriefer()).run(MORNING))
    spans = {s.name: s for s in EXPORTER.get_finished_spans()}
    run = spans["sdp.digest.run"]
    assert run.kind == SpanKind.SERVER
    assert (run.attributes["sdp.digest.sites"], run.attributes["sdp.digest.scheduled"]) == (1, 1)
    assert spans["sdp.digest.figures"].parent.span_id == run.context.span_id
    assert spans["sdp.digest.figures"].attributes["sdp.digest.site"] == "paris-01"


def test_a_failure_before_the_run_is_a_failed_request():
    EXPORTER.clear()

    def unreachable():
        raise TimeoutError("connection timeout expired")

    with pytest.raises(TimeoutError):
        traced_run(unreachable)
    [run] = [s for s in EXPORTER.get_finished_spans() if s.name == "sdp.digest.run"]
    assert not run.status.is_ok
    assert run.events[0].name == "exception"
