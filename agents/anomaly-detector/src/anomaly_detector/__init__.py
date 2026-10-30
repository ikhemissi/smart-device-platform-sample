"""Model-based analysis jobs (ADR-0011): anomaly detection (M7), site digests (M8) and maintenance (M9), one run per
job execution."""


def main() -> None:
    from .main import run

    raise SystemExit(run())


def site_digest() -> None:
    from .digest.main import run

    raise SystemExit(run())


def maintenance() -> None:
    from .maintenance.main import run

    raise SystemExit(run())
