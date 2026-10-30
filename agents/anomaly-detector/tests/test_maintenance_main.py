from anomaly_detector.maintenance.main import run


def test_placeholder_exits_zero():
    assert run([]) == 0
