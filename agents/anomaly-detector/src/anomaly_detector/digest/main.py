"""Digest job entry point. Default: one run (due and requested digests), then exit (0 = run completed, model failures
included: they are traced and retried by later runs; 1 = run failed). `--dry-run --site <id>`: compute and print a
site's figures and brief for now, without writing anything."""

import argparse
import json
import logging
from datetime import UTC, datetime

from azure.core.credentials import TokenCredential

from .. import db, jobs, telemetry
from .brief import Briefer
from .config import DigestSettings
from .figures import Window
from .run import DigestRun, RunResult, traced_run
from .store import PgSource, PgStore

log = logging.getLogger("site_digest")


def _parse(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="site-digest", description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="print a site's figures and brief; write nothing")
    parser.add_argument("--site", help="dry run: the site")
    parser.add_argument("--no-brief", action="store_true", help="dry run: figures only (no model call)")
    args = parser.parse_args(argv)
    if args.dry_run and not args.site:
        parser.error("--dry-run needs --site")
    return args


def run(argv: list[str] | None = None) -> int:
    args = _parse(argv)
    jobs.configure_logging()
    settings = DigestSettings()
    credential = jobs.credential()
    briefer = Briefer(settings.openai_endpoint, settings.model_deployment, jobs.model_token(credential))
    now = datetime.now(UTC)
    if args.dry_run:
        return _dry_run(args, settings, credential, briefer, now)

    telemetry.configure(settings, credential)

    def execute() -> RunResult:
        with db.connect(settings, credential) as conn:
            return DigestRun(PgSource(conn, settings), PgStore(conn), briefer).run(now)

    try:
        r = traced_run(execute)
        log.info(
            "Digest run completed: sites=%d scheduled=%d requested=%d briefs_added=%d errors=%d",
            r.sites,
            r.scheduled,
            r.requested,
            r.briefs_added,
            r.errors,
        )
        return 0
    except Exception:
        log.exception("Digest run failed")
        return 1
    finally:
        telemetry.flush()


def _dry_run(
    args: argparse.Namespace, settings: DigestSettings, credential: TokenCredential, briefer: Briefer, now: datetime
) -> int:
    with db.connect(settings, credential) as conn:
        figures = PgSource(conn, settings).figures(args.site, Window(now))
    print(json.dumps(figures, ensure_ascii=False, indent=2))
    if not args.no_brief:
        brief = briefer.write(figures)
        print("\n" + (brief.text if brief else "(the model failed: no brief)"))
    return 0
