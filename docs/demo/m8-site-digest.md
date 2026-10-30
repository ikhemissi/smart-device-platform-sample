# Demo guide — M8: Site digest

Shows a daily brief per site: the platform computes the figures, `gpt-6-luna` writes a few sentences from them, and the site page shows both; an Editor can ask for a new one ([PRD-008](../prd/PRD-008-m8-site-digest.md), [ADR-0011](../adr/ADR-0011-model-based-analysis.md)). Duration: about 15 minutes, including up to 5 minutes waiting for a regenerated digest.

## Before the demo

The environment must have M1–M8 deployed and the simulator running:

```bash
azd env select sdp-dev
azd provision && azd deploy        # or: azd up
eval "$(azd env get-values)"
```

Every configured site (`DIGEST_SITES`, default all) gets its digest once a day after 06:00 UTC; the job runs every 5 minutes. After a first deployment later in the day, the first digests appear within 5 minutes.

**To make the digest interesting, inject anomalies an hour before** (see the [M7 demo guide](m7-anomaly-detection.md)) and, if you like, create a threshold alert (M5), then ask for a new digest of that site (section 3) so it includes them.

Requirements on the presenter's machine: `az`, `azd`, Python 3 with [uv](https://docs.astral.sh/uv/) (for the dry run).

## 1. Why a digest?

A site manager wants to know, in one look, whether the site is fine — not to open ten devices, the alerts and the anomalies. The digest gives the last 24 hours compared with the previous week:

| Part | Who |
|---|---|
| Figures: devices reporting and silent, metric averages and changes, alerts, anomalies, rejected messages, certificates expiring | computed by the platform |
| Brief: at most six sentences | written by `gpt-6-luna` **from the figures only** |

## 2. Read a site's digest

1. Sign in to the portal and open **Sites → paris-01**.
2. The **Digest** card shows the brief, the period and when it was generated. Device ids in the brief are links: open one.
3. Below the brief, the **figures**: devices (with any silent one and since when), the metrics table (average, previous 7 days, change), alerts, anomalies by classification and status, rejected messages, certificates expiring within 30 days.
4. Point out that every number of the brief is in the figures: the platform checks it on every digest (`sdp.digest.unsupported_numbers` in the trace is 0).

A site without digests (not in `DIGEST_SITES`) says so; with `DIGEST_ENABLED=false` the card is not shown at all.

## 3. Regenerate

1. As an **Editor**, select **Regenerate** on the Digest card.
2. The card says a new digest is being generated, by whom and when, and refreshes on its own.
3. Within 5 minutes (the job's next run), the new digest appears, marked as requested by you, covering the 24 hours before its generation.

A **Reader** has no Regenerate action, and the API refuses with 403. The device API only records the request; the job does the work, so the API never needs permissions on the job itself.

## 4. Ask the assistant

Open the **Assistant** and ask *What happened at lyon-01 in the last day?* It answers from the site's latest digest, with its period and generation time, and cites the site.

## 5. Behind the scenes

**Dry run.** Compute the figures and the brief of a site now, without writing anything:

```bash
scripts/anomaly-env.sh                       # .env with your Entra identity
cd agents/anomaly-detector
uv run site-digest --dry-run --site paris-01
```

**Traces.** In Application Insights (Transaction search, role `sdp-site-digest`), a request `sdp.digest.run` counts the sites processed and the digests generated (scheduled, requested); its children are `sdp.digest.figures` (one per site) and `sdp.digest.brief` with the token usage (about 1–2 s and a few hundred tokens per site).

**Failures are not hidden.** If the model fails, the digest is stored with its figures and the card says the brief is unavailable; a later run adds it.

## After the demo

Nothing to clean up: digests are kept 30 days, like the measurements.
