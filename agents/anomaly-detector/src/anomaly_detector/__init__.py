"""Anomaly detector (M7, ADR-0011): one detection cycle per run of the scheduled Container Apps job."""


def main() -> None:
    from .main import run

    raise SystemExit(run())
