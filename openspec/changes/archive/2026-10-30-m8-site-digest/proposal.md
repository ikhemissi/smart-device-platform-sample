# Proposal

## Why

Site managers and operators open a site in the portal to know whether it is fine, but today they must open each device, the alerts and the anomalies to find out. [PRD-008](../../../docs/prd/PRD-008-m8-site-digest.md) asks for a daily brief per site — figures computed by the platform, written up in a few sentences by `gpt-6-luna` — following [ADR-0011](../../../docs/adr/ADR-0011-model-based-analysis.md) (scheduled Python job, statistics compute, the LLM only writes, every output recorded).

## What Changes

- **Digest job** in the anomaly detector's Python project (`agents/anomaly-detector`, second entry point and Container Apps job, every 5 minutes): for each configured site (`DIGEST_SITES`, default all) it generates the day's digest once 06:00 UTC has passed, and any digest an Editor requested.
- **Figures** computed in code over the last 24 hours compared with the 7 days before: devices reporting and silent, per-metric site averages and changes, alerts, anomalies by status, rejected messages, site certificates expiring within 30 days.
- **Brief** written by `gpt-6-luna` from the figures only (a few sentences, English, devices named by id); stored with the figures, the model and the period. If the model fails, the figures are stored without a brief and the next run retries.
- **Data**: migration V9 — `sdp.site_digests` and `sdp.site_digest_requests`; role `sdp_insights` reads alerts, rejected messages and site certificates, writes digests; role `sdp_catalog` (device API) reads digests and records requests.
- **Platform API** (device-api): `GET /api/v1/sites/{siteId}/digest` (Read), `POST /api/v1/sites/{siteId}/digest/requests` (Write; 202, one pending request per site).
- **Portal**: *Digest* card on the site page (brief, period, generated time, figures, links to the devices mentioned; *Regenerate* for Editors; "being generated" while a request is pending).
- **Assistant**: `get_site_digest` tool (read-only, user's token), registered where digests run.
- **Infrastructure**: second job (same image, same identity as the anomaly detector), settings `DIGEST_ENABLED` (default on) and `DIGEST_SITES`; the digest does not need `Microsoft-Decision-1`.
- CI, README, guidelines, demo guide.

## Capabilities

### New Capabilities

- `site-digest`: daily per-site brief — figures, generation, storage, API, portal card, on-demand regeneration, failure handling, settings and observability.

### Modified Capabilities

- `device-analyst-agent`: the assistant can return a site's latest digest.

## Impact

- New: `agents/anomaly-detector` digest module and entry point; migration V9; device-api `digest` package and OpenAPI operations (imported by API Management); portal card; agent tool.
- Infrastructure: one Container Apps job, settings, azd hook (the image is shared with the anomaly detector, built by the same script).
- Cost: one LLM call per site per day plus requested ones (a few thousand tokens each).
- No change to ingestion, alerting or anomaly detection behaviour.
