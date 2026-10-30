import pytest

from anomaly_detector.maintenance.main import _parse


def test_dry_run_options():
    args = _parse(["--dry-run", "--site", "paris-01", "--no-models"])
    assert (args.dry_run, args.site, args.no_models) == (True, "paris-01", True)
    assert _parse([]).dry_run is False


@pytest.mark.parametrize("argv", [["--site", "paris-01"], ["--no-models"]])
def test_dry_run_options_need_dry_run(argv):
    with pytest.raises(SystemExit):
        _parse(argv)
