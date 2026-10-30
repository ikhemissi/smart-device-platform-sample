from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

import pytest
from opentelemetry.trace import StatusCode

from anomaly_detector.decision import DecisionError
from anomaly_detector.maintenance.battery import Forecast
from anomaly_detector.maintenance.lifecycle import QueueItem, change, reasons
from anomaly_detector.maintenance.models import Probability, WorkOrder
from anomaly_detector.maintenance.run import DeviceInput, MaintenanceRun, RunResult, traced_run
from anomaly_detector.maintenance.signals import MaintenanceDevice, Period, Signals

from .tracing import EXPORTER

NOW = datetime(2026, 10, 29, 12, 0, tzinfo=UTC)
HEALTHY = Signals(0.4, Period(2880, 30.0, 32.0), Period(20160, 30.0, 95.0), 0)


def entry(device_id, days_left=3.0, signals=HEALTHY):
    battery = Forecast("battery_voltage", "V", 2.65, -0.05, days_left, NOW - timedelta(days=5), 2.5)
    return DeviceInput(MaintenanceDevice(device_id, "paris-01", None, "ruuvi-v5", NOW), battery, signals)


@dataclass
class FakeSource:
    entries: list[DeviceInput]

    def inputs(self, now):
        return self.entries


@dataclass
class FakeStore:
    items: dict[int, dict] = field(default_factory=dict)
    assessments: dict[str, float] = field(default_factory=dict)
    done: dict[str, datetime] = field(default_factory=dict)

    def queue(self):
        return {i["device"]: QueueItem(id, i["device"], i["status"], i["work_order_at"])
                for id, i in self.items.items() if i["status"] in ("open", "planned")}  # fmt: skip

    def done_since(self, since):
        return {d: at for d, at in self.done.items() if at >= since}

    def save_assessment(self, entry, probability, now):
        self.assessments[entry.device.device_id] = probability.value

    def open_item(self, entry, probability, reasons, work_order, now):
        device_id = entry.device.device_id
        assert all(i["device"] != device_id or i["status"] not in ("open", "planned") for i in self.items.values())
        item_id = len(self.items) + 1
        self.items[item_id] = {"device": device_id, "status": "open", "probability": probability, "reasons": reasons,
                               "work_order": work_order and work_order.text,
                               "work_order_at": now if work_order else None, "updated_at": now}  # fmt: skip
        return item_id

    def update_item(self, item, entry, probability, reasons, work_order, now):
        stored = self.items[item.id]
        stored.update(probability=probability, reasons=reasons, updated_at=now)
        if work_order:
            stored.update(work_order=work_order.text, work_order_at=now)

    def resolve_item(self, item, now):
        self.items[item.id].update(status="resolved", resolved_at=now)


@dataclass
class FakeAssessor:
    probabilities: dict[str, float]
    failing: set[str] = field(default_factory=set)

    def probability(self, state):
        device_id = state["device"]["id"]
        if device_id in self.failing:
            raise DecisionError("throttled")
        return Probability(self.probabilities[device_id], "microsoft-decision-1", input_tokens=10)


@dataclass
class FakeWriter:
    fail: bool = False
    calls: list[dict] = field(default_factory=list)

    def write(self, data):
        self.calls.append(data)
        return None if self.fail else WorkOrder(f"Replace the battery of {data['device']['id']}.", "gpt-6-luna")


def run(store, probabilities, entries=None, writer=None, now=NOW, failing=()):
    entries = entries or [entry(d) for d in probabilities]
    writer = writer or FakeWriter()
    r = MaintenanceRun(FakeSource(entries), store, FakeAssessor(probabilities, set(failing)), writer, 0.5).run(now)
    return r, writer


def test_open_an_item_with_reasons_and_work_order():
    store = FakeStore()
    r, writer = run(store, {"a": 0.93, "b": 0.1})
    assert (r.devices, r.assessed, r.opened, r.work_orders, r.errors) == (2, 2, 1, 1, 0)
    item = store.items[1]
    assert (item["device"], item["status"], item["probability"]) == ("a", "open", 0.93)
    assert item["reasons"][0]["rule"] == "battery_days_left"
    assert item["work_order"] == "Replace the battery of a."
    assert writer.calls[0]["reasons"] == item["reasons"]
    assert store.assessments == {"a": 0.93, "b": 0.1}


def test_later_assessment_updates_the_item_and_keeps_one_per_device():
    store = FakeStore()
    run(store, {"a": 0.93})
    r, writer = run(store, {"a": 0.97}, now=NOW + timedelta(hours=1))
    assert (r.opened, r.updated, r.work_orders) == (0, 1, 0)
    assert len(store.items) == 1
    assert store.items[1]["probability"] == 0.97
    assert store.items[1]["work_order_at"] == NOW
    assert writer.calls == []


def test_work_order_refreshed_after_24_hours():
    store = FakeStore()
    run(store, {"a": 0.93})
    r, _ = run(store, {"a": 0.9}, now=NOW + timedelta(hours=24))
    assert (r.updated, r.work_orders) == (1, 1)
    assert store.items[1]["work_order_at"] == NOW + timedelta(hours=24)


def test_missing_work_order_retried_by_the_next_run():
    store = FakeStore()
    r, _ = run(store, {"a": 0.93}, writer=FakeWriter(fail=True))
    assert (r.opened, r.errors) == (1, 1)
    assert store.items[1]["work_order"] is None
    r, _ = run(store, {"a": 0.93}, now=NOW + timedelta(hours=1))
    assert store.items[1]["work_order"] == "Replace the battery of a."


def test_planned_item_is_updated_too():
    store = FakeStore()
    run(store, {"a": 0.93})
    store.items[1]["status"] = "planned"
    r, _ = run(store, {"a": 0.8}, now=NOW + timedelta(hours=1))
    assert r.updated == 1 and store.items[1]["status"] == "planned"


def test_probability_falls_resolves_the_item():
    store = FakeStore()
    run(store, {"a": 0.93})
    r, _ = run(store, {"a": 0.05}, now=NOW + timedelta(hours=2))
    assert r.resolved == 1
    assert store.items[1]["status"] == "resolved"
    assert store.items[1]["resolved_at"] == NOW + timedelta(hours=2)
    r, _ = run(store, {"a": 0.05}, now=NOW + timedelta(hours=3))
    assert r.resolved == 0


def test_done_blocks_the_device_for_7_days():
    store = FakeStore(done={"a": NOW - timedelta(days=6, hours=23)})
    r, _ = run(store, {"a": 0.93})
    assert (r.assessed, r.opened) == (1, 0)
    r, _ = run(store, {"a": 0.93}, now=NOW + timedelta(hours=1))
    assert r.opened == 1


def test_decision_failure_keeps_the_previous_assessment():
    store = FakeStore(assessments={"a": 0.9})
    run(store, {"a": 0.93})
    r, _ = run(store, {"a": 0.0}, failing={"a"}, now=NOW + timedelta(hours=1))
    assert (r.assessed, r.errors, r.resolved) == (0, 1, 0)
    assert store.assessments["a"] == 0.93
    assert store.items[1]["status"] == "open"


def test_reasons_cross_rules_or_fall_back():
    battery = Forecast("battery_voltage", "V", 2.9, -0.002, 200.0, NOW, 2.5)
    silent = Signals(42.0, Period(1500, 30.0, 600.0), Period(20160, 30.0, 95.0), 6)
    rules = [r["rule"] for r in reasons(battery, silent, 0.8)]
    assert rules == ["silent", "reporting_gaps", "rejected_messages"]
    gaps = reasons(battery, silent, 0.8)[1]
    assert (gaps["value"], gaps["longest_gap_s"], gaps["usual_interval_s"]) == (20.0, 600.0, 30.0)
    assert [r["rule"] for r in reasons(battery, HEALTHY, 0.6)] == ["battery_forecast", "probability"]
    assert reasons(None, HEALTHY, 0.6) == [{"rule": "probability", "value": 0.6}]


def test_change_rules():
    item = QueueItem(1, "a", "open", NOW - timedelta(hours=1))
    assert change(0.5, 0.5, None, None, NOW).action == "open"
    assert change(0.49, 0.5, None, None, NOW).action == "none"
    assert change(0.49, 0.5, item, None, NOW).action == "resolve"
    assert change(0.9, 0.5, item, None, NOW).write_work_order is False
    assert change(0.9, 0.5, QueueItem(1, "a", "open", None), None, NOW).write_work_order is True
    assert change(0.9, 0.5, None, NOW - timedelta(days=7), NOW).action == "open"


def test_trace_of_a_run_with_children():
    EXPORTER.clear()
    store = FakeStore()
    result = traced_run(lambda: run(store, {"a": 0.93, "b": 0.1})[0])
    spans = EXPORTER.get_finished_spans()
    root = next(s for s in spans if s.name == "sdp.maintenance.run")
    assert root.attributes["sdp.maintenance.opened"] == 1
    assert root.attributes["sdp.maintenance.assessed"] == 2
    children = [s.name for s in spans if s.parent and s.parent.span_id == root.context.span_id]
    assert children.count("sdp.maintenance.decide") == 2
    assert result.opened == 1


def test_failed_run_and_errors_mark_the_request():
    EXPORTER.clear()
    traced_run(lambda: RunResult(devices=1, errors=1))
    assert EXPORTER.get_finished_spans()[-1].status.status_code == StatusCode.ERROR
    EXPORTER.clear()
    with pytest.raises(OSError):
        traced_run(lambda: (_ for _ in ()).throw(OSError("database down")))
    assert EXPORTER.get_finished_spans()[-1].status.status_code == StatusCode.ERROR
