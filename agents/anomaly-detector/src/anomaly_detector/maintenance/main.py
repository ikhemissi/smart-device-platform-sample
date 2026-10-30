"""Maintenance job entry point (placeholder until the job is implemented: exits 0)."""

import logging

log = logging.getLogger("maintenance")


def run(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    log.info("Maintenance job not implemented yet: nothing to do")
    return 0
