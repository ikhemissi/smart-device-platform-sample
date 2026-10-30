from datetime import UTC, datetime, timedelta

from anomaly_detector.digest.figures import DeviceRow, MetricRow, SiteData, Window, compute

AT = datetime(2026, 10, 30, 6, 0, tzinfo=UTC)


def site(**overrides) -> SiteData:
    data = {
        "site_id": "paris-01",
        "site_name": "Paris",
        "devices": [
            DeviceRow("paris-01-a", "Cold room", AT - timedelta(minutes=1)),
            DeviceRow("paris-01-b", None, AT - timedelta(hours=3)),
            DeviceRow("paris-01-c", None, AT - timedelta(minutes=14)),
        ],
        "metrics": [
            # Rolled-up hours and the recent raw readings of the period, and the comparison week.
            MetricRow("temperature", True, 3, 21.0 * 100, 100, 18.0, 24.0),
            MetricRow("temperature", True, 2, 23.0 * 20, 20, 20.0, 26.5),
            MetricRow("temperature", False, 3, 20.0 * 700, 700, 15.0, 25.0),
            MetricRow("humidity", True, 3, 50.0 * 10, 10, 40.0, 60.0),
        ],
        "units": {"temperature": "°C", "humidity": "%"},
        "alerts": [
            {
                "deviceId": "paris-01-a",
                "metric": "temperature",
                "direction": "ABOVE",
                "status": "CLOSED",
                "openedAt": AT - timedelta(hours=5),
                "closedAt": AT - timedelta(hours=4, minutes=48),
                "worstValue": 9.234,
            }
        ],
        "open_alerts": 0,
        "anomalies": [
            {
                "deviceId": "paris-01-a",
                "metric": "temperature",
                "kind": "deviation",
                "status": "confirmed",
                "classification": "real_event",
                "confidence": 0.912,
                "startedAt": AT - timedelta(hours=2),
                "endedAt": AT - timedelta(hours=1),
            },
            {
                "deviceId": "paris-01-c",
                "metric": "humidity",
                "kind": "jump",
                "status": "dismissed",
                "classification": "expected_pattern",
                "confidence": 0.9,
                "startedAt": AT - timedelta(hours=8),
                "endedAt": AT - timedelta(hours=7),
            },
        ],
        "rejected": {"unknown_format": 3, "truncated": 1},
        "certificates": [
            {"slot": "primary", "subject": "CN=paris-01 CA", "notAfter": AT + timedelta(days=12, hours=1)},
            {"slot": "secondary", "subject": "CN=paris-01 CA 2", "notAfter": AT + timedelta(days=200)},
        ],
    }
    return SiteData(**(data | overrides))


def test_periods_cover_the_last_day_and_the_week_before():
    figures = compute(site(), Window(AT))
    assert figures["period"] == {"from": "2026-10-29T06:00:00Z", "to": "2026-10-30T06:00:00Z"}
    assert figures["comparison"] == {"from": "2026-10-22T06:00:00Z", "to": "2026-10-29T06:00:00Z"}


def test_devices_silent_for_more_than_15_minutes():
    devices = compute(site(), Window(AT))["devices"]
    assert (devices["total"], devices["reporting"]) == (3, 2)
    assert devices["silent"] == {
        "total": 1,
        "items": [{"deviceId": "paris-01-b", "name": None, "lastSeenAt": "2026-10-30T03:00:00Z"}],
    }


def test_metric_averages_combine_rollups_and_recent_readings_and_compare_with_the_week_before():
    metrics = {m["metric"]: m for m in compute(site(), Window(AT))["metrics"]}
    temperature, humidity = metrics["temperature"], metrics["humidity"]
    assert "comparisonAverage" not in humidity
    assert temperature == {
        "metric": "temperature",
        "unit": "°C",
        "devices": 3,
        "average": 21.33,  # (2100 + 460) / 120
        "min": 18.0,
        "max": 26.5,
        "comparisonAverage": 20.0,
        "change": 1.33,
        "changePercent": 6.7,
    }


def test_alerts_anomalies_rejections_and_certificates():
    figures = compute(site(open_alerts=2), Window(AT))
    assert figures["alerts"]["openNow"] == 2
    assert figures["alerts"]["items"][0] | {"openedAt": None} == {
        "deviceId": "paris-01-a", "metric": "temperature", "direction": "above", "status": "closed", "openedAt": None,
        "closedAt": "2026-10-30T01:12:00Z", "worstValue": 9.23,
    }  # fmt: skip
    anomalies = figures["anomalies"]
    assert anomalies["byStatus"] == {"confirmed": 1, "dismissed": 1}
    assert [(a["deviceId"], a["confidencePercent"]) for a in anomalies["items"]] == [("paris-01-a", 91)]
    assert figures["rejectedMessages"] == {"total": 4, "byReason": {"truncated": 1, "unknown_format": 3}}
    assert figures["certificates"]["expiringWithin30Days"] == [
        {"slot": "primary", "subject": "CN=paris-01 CA", "notAfter": "2026-11-11T07:00:00Z", "daysLeft": 12}
    ]


def test_lists_are_bounded_with_their_total():
    many = [DeviceRow(f"d{i:02}", None, AT - timedelta(hours=1)) for i in range(25)]
    silent = compute(site(devices=many), Window(AT))["devices"]["silent"]
    assert silent["total"] == 25 and len(silent["items"]) == 20


def test_a_quiet_site():
    figures = compute(site(alerts=[], anomalies=[], rejected={}, certificates=[], metrics=[]), Window(AT))
    assert figures["metrics"] == []
    assert figures["alerts"] == {"openNow": 0, "total": 0, "items": []}
    assert figures["anomalies"] == {"byStatus": {}, "total": 0, "items": []}
    assert figures["rejectedMessages"] == {"total": 0, "byReason": {}}
