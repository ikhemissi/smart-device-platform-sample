"""Digest job entry point (placeholder until the digest run is implemented): exits successfully without work."""

import logging

log = logging.getLogger("site_digest")


def run(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    log.info("Site digest run: nothing to do yet")
    return 0
