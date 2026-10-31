# Design: M9 — Battery forecast and maintenance queue

## Context

See proposal.md for the motivation and the delta specs for the behaviour. This change applies [ADR-0011](../../../docs/adr/ADR-0011-model-based-analysis.md) a third time and reuses what M7 and M8 built in `agents/anomaly-detector`: settings, PostgreSQL with an Entra token (`db.py`), telemetry (request span started before connecting), the decision client pattern (`decision.py`), the Responses API pattern (`explanation.py`, `digest/brief.py`), the image build script and the workload identity `id-anomaly-detector` (Foundry User, PostgreSQL role `sdp_insights`). Conventions: [implementation guidelines](../../../docs/architecture/implementation-guidelines.md) §1, §3, §5, §6, §10 (*Model-based analysis*).

Facts that shape the approach:

- Battery: `battery_voltage` (V; Ruuvi and Cayenne formats) or `battery_level` (%; BTHome) in `sdp.measurements.metrics`, hourly rollups in `sdp.metric_rollups_hourly` (`sum_value / sample_count`).
- The simulator's battery (`libs/device-fleet/.../ReadingModel.java`) is computed from the simulator's start time (`batteryStartVolts - 0.002 * days`): it resets on every restart and declines too slowly for a demo. The 30-day history in `sdp-dev` therefore holds slow declines with jumps at restarts.
- `caj-maint-<token>` is already the database maintenance job: the new job is `caj-upkeep-<token>`.
- The implementation is split between the **GitHub Copilot cloud agent** (code and offline tests, no Azure access) and a session with access to `sdp-dev` (provisioning, live checks, tuning, user checks). Tasks are marked accordingly (D12).

## Goals / Non-Goals

**Goals:** a queue the field team can trust (every reason is a computed figure, every probability recorded); forecasts robust to battery replacements and simulator restarts; a demo where at least one device per site visibly ages.

**Non-Goals:** notifications; scheduling or assigning visits; forecasts of other components; learning from Editors' statuses.

## Decisions

### D1. Third job in the model-based analysis project
Package `anomaly_detector.maintenance`, entry point `maintenance` (pyproject script), Container Apps job `caj-upkeep-<token>`, cron `0 * * * *`, parallelism 1, 10-minute timeout, no retry; same image and identity as the other two; role name `sdp-maintenance`. One run: assess every device of the configured sites (`SDP_MAINTENANCE_SITES`, empty = all), then update the queue. Settings class `MaintenanceSettings` (database, OpenAI endpoint and model, decision endpoint and deployment, sites, confidence).

### D2. Battery forecast (pure Python, unit-testable)
Input: the device's hourly battery averages of the last 14 days (rollups) plus the recent raw readings not rolled up yet (same approach as `digest/data.py`).
1. **Segment**: start after the last *sudden change*: a step between consecutive hourly averages larger than 0.1 V (or 5 percentage points for a level) in either direction — a replacement, or the simulator's model change.
2. **Trend**: weighted least squares of value against time over the segment, weights `0.5 ** (age_days / 3)` (half-life 3 days: recent days count more — the "piecewise" choice).
3. **Output**: `level` (fitted value now), `slope_per_day`, `days_left = (level - cutoff) / -slope` when `slope < -0.001 V/day` (or `-0.05 %/day`), else none; `since` (segment start); cut-offs 2.5 V and 10 %.
4. Less than 12 hours of readings in the segment → level only (latest hourly average), no trend. (24 hours at first; the live check showed that every Ruuvi battery restarted a segment when the new simulator was deployed, and 12 hours of an aging battery already give a clear slope.)

### D3. Reporting signals (SQL, per device)
Over the last 24 hours (P) and the 7 days before (C), from `sdp.measurements.measured_at` with `lag()` per device: message count, median interval and longest gap in P and C; `minutes_since_last` from `sdp.devices.last_seen_at`; rejected messages in P from `sdp.rejected_messages` by `device_id`.

### D4. Intervention probability (Microsoft-Decision-1)
One `noul` question per device and run: *"Will this device need an on-site intervention (battery replacement, repair or reset) within the next 7 days?"*, criteria `true`: "the battery reaches its cut-off within 7 days, or the device is failing to report", `false`: "the device will keep working without a visit for 7 days". State: device (id, site, payload format), battery forecast (D2) and signals (D3) only. Stored: `p(true)`, model, version `1`. A failure leaves the previous assessment (with its time) and counts as an error. The client follows `decision.py` (same endpoint, deployment, Entra token); the request and response format is the one already used there (`answers.<name>.noul` holds the probability).

**Live check (task 2.5):** with the first wording, the model read a missing battery as a risk (p ≈ 0.9 for every Cayenne device) and rated failing communication low (silent 6 h: 0.08; gaps 20 times the usual interval: 0.09). The state now says `battery: {"reported": false}` and the `true` criterion is explicit: battery cut-off within 7 days, or communication failing — silent for more than an hour, gaps over the last 24 hours more than ten times the usual interval, or 20 or more rejected messages in 24 hours. Measured probabilities (live, real signals and forecasts):

| Case (live, Microsoft-Decision-1) | Probability |
|---|---|
| Aging battery, 3.5 / 5 days left | 0.98 / 0.99 |
| Aging battery, 9 / 12 days left | 0.27 / 0.15 |
| Healthy battery (300 days), no trend yet | 0.01 / 0.03 |
| No battery: healthy signals, silent 30 min | 0.01 / 0.01 |
| No battery: silent 6 h | 0.82 |
| No battery: gaps 20 times the usual interval | 0.50 |
| No battery: 40 / 8 rejected messages in 24 h | 0.99 / 0.01 |

### D5. Queue lifecycle
`MAINTENANCE_CONFIDENCE` default 0.4 (tuned, see D4). Per device after its assessment:
- p ≥ confidence, no `open`/`planned` item, and no item marked `done` in the last 7 days → **open** an item with the reasons and a work order (D6).
- p ≥ confidence and an `open`/`planned` item → update its probability, reasons and `updated_at`; refresh the work order when it is older than 24 hours.
- p < confidence and an `open`/`planned` item → **resolved** (by the platform, `resolved_at`).
Reasons: the computed figures that cross a rule, with their values — `days_left ≤ 7`, `minutes_since_last > 15`, longest gap in P ≥ 5 × median interval of C, rejected messages in P ≥ 5; if none crosses, the battery forecast and the probability.

### D6. Work order (gpt-6-luna)
Responses API, `store=false`, reasoning `low`; instructions: at most four sentences, English, plain text, start with the action (replace the battery, inspect the device, check its connectivity), name the device id and site, give the numbers of the reasons with units, and the urgency (days left); only facts present in the input. Input: device, battery forecast, signals, reasons, probability. A failure leaves the item without work order; later runs retry. Reuse the grounding check of `digest/brief.py` (`unsupported_numbers`) and record its count on the span.

### D7. Data model (migration V10)
- `sdp.device_maintenance`: `device_id` (PK, FK devices, cascade), `site_id`, `assessed_at`, `battery` jsonb (metric, unit, level, slope_per_day, days_left, since, cutoff) nullable, `signals` jsonb, `probability`, `decision_model`, `decision_model_version`.
- `sdp.maintenance_items`: `id`, `device_id`, `site_id`, `status` (`open`, `planned`, `done`, `resolved`), `probability`, `reasons` jsonb, `battery` jsonb, `work_order`, `work_order_model`, `work_order_at`, `opened_at`, `updated_at`, `resolved_at`, `status_by`, `status_at`, `status_comment` (≤ 1000); unique partial index on `device_id` where status in (`open`, `planned`); index `(site_id, status, probability desc)`.
- Grants: `sdp_insights` select/insert/update both tables (and select on `sdp.measurements` gaps — already granted); `sdp_catalog` select both, update `(status, status_by, status_at, status_comment, updated_at)` of items.
- Retention: the maintenance task of the migrator deletes `done`/`resolved` items older than the retention (30 days).

### D8. API (device-api)
- `GET /api/v1/maintenance?siteId&status&limit` (Read; statuses repeated or comma-separated, default `open,planned`; most likely first; limit ≤ 200) → items with device name, site, probability, battery summary, reasons, work order, status fields.
- `GET /api/v1/devices/{deviceId}/maintenance` (Read) → `{enabled, assessment | null, item | null}` (`enabled` false when maintenance is off or the site is not configured).
- `POST /api/v1/maintenance/{itemId}/status` (Write) `{status: planned | done, comment?}` → the item; 409 for a `done` or `resolved` item; status by from the token.
- Settings `SDP_MAINTENANCE_ENABLED`, `SDP_MAINTENANCE_SITES`; OpenAPI regenerated.

### D9. Portal
`config.json` gains `maintenanceEnabled` (`UPKEEP_JOB_NAME` non-empty; `PORTAL_MAINTENANCE` overrides). The job's output is `UPKEEP_JOB_NAME`, after its resource name: `MAINTENANCE_JOB_NAME` already names the database maintenance job (`caj-maint-<token>`), used by the migrator build script and the post-provision hook. *Maintenance* page (navigation): site and status filters, table most likely first (device link, site, probability %, days left, reasons, work order, status), *Planned* / *Done* with comment for Editors. Device page: *Battery and maintenance* card (level, trend per day, days left, probability, assessed at, queue item). Patterns: the M7 anomalies page and card.

### D10. Site digest and assistant
- Digest figures gain `maintenance` (open and planned counts, the 5 most likely items with device, probability %, days left) when `SDP_MAINTENANCE_ENABLED` is true on the digest job; the brief instructions mention the queue.
- Assistant: `list_maintenance(site_id?, status?)` read-only tool, registered when `SDP_MAINTENANCE_ENABLED` is true; cites the devices.

### D11. Simulator
- **Battery from the time**: per device, a sawtooth: `voltage = new - rate * ((days_since_epoch + phase) mod period)`, with `new = 3.05 V`, plus noise ±0.002 V. Normal devices: `rate = 0.0015 V/day`, `period = 400 days`, `phase` from the seed in [0, 200] days (they never reach the cut-off during a demo). **Aging devices** (`SDP_SIM_AGING_DEVICES`, comma-separated ids): `rate = 0.035 V/day`, `period = 16 days` (3.05 V → 2.49 V, then a new battery), phase from the seed. Battery level derived from the voltage as today.
- The phase and rate are drawn from the device seed after the existing draws (the device's other values and MAC stay unchanged).
- Terraform setting `SIMULATOR_AGING_DEVICES`, default one Ruuvi device per demo site: `<site>-ruuvi5-003`.

### D12. Delegation to the GitHub Copilot cloud agent
- `.github/workflows/copilot-setup-steps.yml` (generated by `openspec update` with `githubCopilot.cloudAgent: true`, then extended): JDK 25, Node 24, uv 0.13.5, OpenSpec CLI, and dependencies pre-fetched (Maven offline, `npm ci`, `uv sync` for both Python projects, the Playwright container image) before the agent's firewall applies. Testcontainers uses the runner's Docker.
- `.github/agents/openspec.agent.md` (generated) helps the agent use the OpenSpec CLI.
- Tasks marked **[cloud agent]** are implemented and verified offline with the commands of AGENTS.md (unit and integration tests, offline Playwright, `terraform validate`, `openspec validate`), one conventional commit per task on the PR branch, ticking only those tasks. Tasks marked **[Azure]** are left unticked for the session with access to `sdp-dev`: provisioning, deployments, live checks, tuning, user checks.

## Risks / Trade-offs

- [Decision-1's probability on these signals is uncalibrated] → the confidence is a setting, chosen with a dry run in `sdp-dev` (task 2.6); the signals and probability are recorded for review.
- [History before the simulator change has slow declines and restart jumps] → segments start after any sudden change (D2); forecasts are meaningful one day after the new simulator is deployed.
- [The cloud agent cannot observe live model behaviour] → its tasks use mocked models; the Azure tasks include live checks and a tuning step, and expect a round of fixes as in M7 and M8.
- [Hourly decision calls (one per device)] → 30 calls per hour in the demo, within the deployment's capacity (30 requests per minute).

## Migration Plan

Additive: `azd provision --preview` (job, settings, device-api and agent env, API operations, Decision-1 condition), `azd provision` (migration V10, image build), `azd deploy device-simulator device-api device-analyst web`. Rollback: `MAINTENANCE_ENABLED=false` and provision (removes the job; data kept until retention).
