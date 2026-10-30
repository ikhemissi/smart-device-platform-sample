# Design: M8 — Site digest

## Context

See proposal.md for the motivation and the delta specs for the behaviour. This change applies [ADR-0011](../../../docs/adr/ADR-0011-model-based-analysis.md) a second time (scheduled Python job, figures computed in code, the LLM only writes, every output recorded) and reuses what M7 built: the `agents/anomaly-detector` project (settings, PostgreSQL with an Entra token, OpenTelemetry, Responses API client), its image build script and its workload identity. Conventions: [implementation guidelines](../../../docs/architecture/implementation-guidelines.md) §1 (Python), §3 (data), §5 (observability), §6 (APIs), §10 (AI, model-based analysis).

Facts that shape the approach:

- Hourly rollups (`sdp.metric_rollups_hourly`, M6) give per-device, per-metric sums and counts per UTC hour; `sdp.devices.last_seen_at` gives silence (15 minutes, as the fleet status); `sdp.alert_events` (M5), `sdp.anomalies` (M7), `sdp.rejected_messages` (M1) and `sdp.site_certificates` (M4, `not_after`) hold the rest.
- `sdp.alert_events.recipients` holds email addresses: the digest must not read them.
- The device API runs as `sdp_catalog` and must keep data-plane access only (no permission to start jobs).

## Goals / Non-Goals

**Goals:** a brief anyone can trust because every figure is computed and shown next to it; cheap (one LLM call per site per day); regenerable on demand without new control-plane permissions.

**Non-Goals:** email delivery; other periods or languages; a fleet-wide digest; the maintenance queue (M9 adds it to the figures).

## Decisions

### D1. Second job in the detector's project
Module `site_digest` and entry point `site-digest` in `agents/anomaly-detector` (same image, same identity `id-anomaly-detector`, which already holds Foundry User and `sdp_insights`). Container Apps job `caj-digest-<token>`, cron `*/5 * * * *`, parallelism 1, 4-minute timeout, no retry. Each run: generate the **scheduled** digests that are due, then the **requested** ones, then add missing briefs (model failures) of the day's digests. The build script updates both jobs that exist. Alternative: a separate project — duplicates database, telemetry and model code for a few hundred lines.

### D2. Due digests
A configured site is due when now ≥ 06:00 UTC and it has no scheduled digest generated since today 06:00 UTC. Configured sites: `SDP_DIGEST_SITES` (comma-separated; empty = every site in `sdp.sites`). A pending request (`processed_at` null) is served whatever the time; the request row is marked processed with the digest id in the same transaction as the digest insert.

### D3. Figures (computed in SQL/Python, stored as JSON)
Period P = [t − 24 h, t), comparison C = [t − 8 d, t − 24 h) where t is the generation time.

| Figure | Source |
|---|---|
| Devices: total, reporting, silent (id, name, last seen) | `sdp.devices` (silent: `last_seen_at` older than 15 min at t) |
| Metrics: per metric of `SDP_DIGEST_METRICS` (default `temperature,humidity,pressure,co2,illuminance,battery_voltage,battery_level`) reported at the site: average over P and C, change (absolute and %), min and max over P, unit | hourly rollups (sum/count), `metric_definitions` |
| Alerts: opened in P (device, metric, direction, status, opened, closed, worst value), still open at t | `sdp.alert_events` without `recipients` |
| Anomalies: in P by status, each with device, metric, kind, classification, confidence, period | `sdp.anomalies` |
| Rejected messages in P, by reason | `sdp.rejected_messages` |
| Certificates expiring within 30 days (slot, subject, `not_after`) | `sdp.site_certificates` |

Lists are bounded (20 items each, with totals) so that the figures and the prompt stay small.

### D4. Brief
`gpt-6-luna`, Responses API, `store=false`, reasoning effort `low`; instructions: at most six sentences, English, plain text, only numbers present in the figures (rounded as given), devices by id, start with the reporting status, then notable metric changes (|change| ≥ 10 %), alerts, anomalies, rejected messages and certificates; say "nothing notable" rather than inventing. Input: the figures JSON only. Stored with the model deployment name. A failure leaves `brief` null (retried by later runs of the same day; the card says the brief is unavailable).

### D5. Data model (migration V9)
- `sdp.site_digests`: `id`, `site_id`, `trigger` (`scheduled`, `requested`), `period_from`, `period_to`, `comparison_from`, `generated_at`, `figures` jsonb, `brief`, `brief_model`, `brief_generated_at`, `requested_by`; index `(site_id, generated_at desc)`.
- `sdp.site_digest_requests`: `id`, `site_id`, `requested_at`, `requested_by`, `processed_at`, `digest_id`; unique partial index on `site_id` where `processed_at is null` (one pending request per site).
- Grants: `sdp_insights` reads `alert_events` (all columns but `recipients`), `rejected_messages` (no payload), `site_certificates`; reads/inserts/updates `site_digests`; reads/updates `site_digest_requests`. `sdp_catalog` reads `site_digests`; reads/inserts `site_digest_requests`.
- Retention: the maintenance job deletes digests and processed requests older than the measurement retention (30 days).

### D6. API (device-api)
- `GET /api/v1/sites/{siteId}/digest` (Read) → `{siteId, enabled, digest: {...} | null, pendingRequest: {requestedAt, requestedBy} | null}`; `enabled` is false when digests are off or the site is not configured (`SDP_DIGEST_ENABLED`, `SDP_DIGEST_SITES` on the device API); 404 for an unknown site.
- `POST /api/v1/sites/{siteId}/digest/requests` (Write) → 202 with the pending request (an existing pending request is returned as is); 409 when the site has no digests; the requester's display name is recorded.
- OpenAPI regenerated; API Management imports it.

### D7. Portal
`config.json` gains `digestsEnabled`. Site page: *Digest* card above the certificates: brief (device ids that appear in the figures become links to the device pages), period and generation time, figures in compact sections (devices, metrics table with change, alerts, anomalies, rejected messages, certificates), *Regenerate* for Editors; while a request is pending the card shows "A new digest is being generated (requested by … at …)" and refetches every 20 s until a newer digest arrives. "This site has no digest" when `enabled` is false; no card when the feature is off.

### D8. Assistant
`get_site_digest(site_id)` read-only tool, registered when `SDP_DIGESTS_ENABLED` is true; returns period, generation time, brief and figures (compacted); cites the site.

### D9. Infrastructure and settings
azd settings → Terraform: `DIGEST_ENABLED` (default on; the job, the portal card and the assistant tool follow), `DIGEST_SITES` (empty = all), `DIGEST_METRICS`. Job env: database, model, `SDP_DIGEST_SITES`, `SDP_DIGEST_METRICS`. device-api env: `SDP_DIGEST_ENABLED`, `SDP_DIGEST_SITES`. The image build step runs when either the detector or the digest job exists.

### D10. Observability
One request span `sdp.digest.run` (SERVER; sites processed, digests generated by trigger, briefs added, errors), child spans `sdp.digest.figures` (site) and `sdp.digest.brief` (`gen_ai.usage.*`); role `sdp-site-digest` (`OTEL_SERVICE_NAME`).

### D11. Dry run
`site-digest --dry-run --site <id>` computes the figures and the brief for now and prints them without writing: used to check the prompt and the figures before deployment, and in the demo guide.

## Risks / Trade-offs

- [The LLM states a number that is not in the figures] → instructions, figures-only input, and a unit test that checks every number of a generated brief against the figures (on recorded live answers); the figures are always shown next to the brief.
- [Regeneration waits up to 5 minutes for the next run] → accepted (user choice) to keep the device API data-plane only; the card says a digest is being generated.
- [A digest job run every 5 minutes mostly does nothing] → the run exits after two small queries; cost is negligible on the Consumption plan.
- [Same identity for two jobs] → both are model-based analysis jobs with the same data needs (ADR-0011); splitting identities later is a Terraform change.

## Migration Plan

Additive: `azd provision --preview` (job, settings, device-api env, API operations), `azd provision` (migration V9, image build), `azd deploy device-api device-analyst web`. Rollback: `DIGEST_ENABLED=false` and provision (removes the job; digests are kept until retention).
