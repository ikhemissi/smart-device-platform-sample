"""Job entry point. Default: one live detection cycle, then exit (0 = completed, model failures included: they are
traced and retried by the next cycle; 1 = cycle failed). `--dry-run`: replay past cycles without writing anything.
"""

import argparse
import logging
import sys
import time
from datetime import UTC, datetime, timedelta

from azure.identity import DefaultAzureCredential, get_bearer_token_provider

from . import db, telemetry
from .config import Settings
from .cycle import CycleResult, Detector, traced_cycle
from .decision import Classification, Classifier, DecisionClient
from .explanation import Explainer
from .pg import PgSource, PgStore
from .replay import ReplaySource, dry_run

log = logging.getLogger("anomaly_detector")

COGNITIVE_SCOPE = "https://cognitiveservices.azure.com/.default"
# Locally, credentials come from developer tools (Azure CLI): allow more than the default 10 s on a busy machine.
DEVELOPER_TOOL_TIMEOUT = 60


def _parse(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="anomaly-detector", description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="replay past cycles; write nothing")
    parser.add_argument("--days", type=float, default=7, help="dry run: period ending now (default 7 days)")
    parser.add_argument("--deviation", type=float, help="override SDP_ANOMALY_DEVIATION")
    parser.add_argument("--confidence", type=float, help="override SDP_ANOMALY_CONFIDENCE")
    parser.add_argument("--classify", action="store_true", help="dry run: classify new episodes (no write)")
    parser.add_argument("--output", help="dry run: write the episodes to this file (JSON lines)")
    return parser.parse_args(argv)


class _Throttled:
    """Dry runs classify in bursts: stay under the deployment's requests per minute."""

    def __init__(self, classifier: Classifier, per_minute: int) -> None:
        self.classifier, self.interval, self.last = classifier, 60 / per_minute, 0.0

    def classify(self, state: dict) -> Classification:
        time.sleep(max(0.0, self.last + self.interval - time.monotonic()))
        self.last = time.monotonic()
        return self.classifier.classify(state)


def run(argv: list[str] | None = None) -> int:
    args = _parse(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logging.getLogger("azure").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    overrides = {k: v for k, v in {"deviation": args.deviation, "confidence": args.confidence}.items() if v is not None}
    settings = Settings(**{Settings.model_fields[k].validation_alias: v for k, v in overrides.items()})
    credential = DefaultAzureCredential(process_timeout=DEVELOPER_TOOL_TIMEOUT)
    token = get_bearer_token_provider(credential, COGNITIVE_SCOPE)
    classifier = DecisionClient(settings.decision_endpoint, settings.decision_deployment, token)
    if args.dry_run:
        return _dry_run(args, settings, credential, _Throttled(classifier, 25) if args.classify else None)

    telemetry.configure(settings, credential)
    explainer = Explainer(settings.openai_endpoint, settings.model_deployment, token)

    def execute() -> CycleResult:
        with db.connect(settings, credential) as conn:
            store = PgStore(conn, settings.sites)
            return Detector(settings, PgSource(conn, settings), store, classifier, explainer).run(datetime.now(UTC))

    try:
        r = traced_cycle(settings.sites, execute)
        log.info(
            "Cycle completed: devices=%d assessed=%d candidates=%d created=%d extended=%d retried=%d decisions=%s "
            "explained=%d errors=%d",
            r.devices, r.assessed, r.candidates, r.created, r.extended, r.retried, dict(r.decisions), r.explained,
            r.errors,
        )  # fmt: skip
        return 0
    except Exception:
        log.exception("Cycle failed")
        return 1
    finally:
        telemetry.flush()


def _dry_run(args: argparse.Namespace, settings: Settings, credential, classifier: Classifier | None) -> int:
    end = datetime.now(UTC).replace(second=0, microsecond=0)
    end -= timedelta(minutes=end.minute % 5)
    start = end - timedelta(days=args.days)
    log.info("Dry run %s → %s, sites=%s deviation=%s", start.isoformat(), end.isoformat(), settings.sites,
             settings.deviation)  # fmt: skip
    with db.connect(settings, credential) as conn:
        source = ReplaySource.load(conn, settings, start, end)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as output:
            per_day = dry_run(settings, source, start, end, classifier, output)
    else:
        per_day = dry_run(settings, source, start, end, classifier)
    columns = sorted({key for counts in per_day.values() for key in counts})
    print("day", *columns, sep="\t")
    for day in sorted(per_day):
        print(day, *(per_day[day][c] for c in columns), sep="\t")
    sys.stdout.flush()
    return 0
