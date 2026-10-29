# Design: M7 — Anomaly detection

## Context

See proposal.md for the motivation and the delta specs for the behaviour. This change applies [ADR-0011](../../../docs/adr/ADR-0011-model-based-analysis.md) (Python jobs; statistics select, the decision model decides, the LLM explains; every decision recorded) for the first time, and relies on [ADR-0004](../../../docs/adr/ADR-0004-telemetry-data-model.md) (measurements, hourly rollups since M6), [ADR-0005](../../../docs/adr/ADR-0005-passwordless-identity.md) (managed identities, Entra-only PostgreSQL and Foundry) and [ADR-0007](../../../docs/adr/ADR-0007-user-authn-authz-and-api-exposure.md) (Read/Write roles, API Management). Conventions: [implementation guidelines](../../../docs/architecture/implementation-guidelines.md) (§1 Python, §5 observability, §10 AI).

Facts that shape the approach:

- The simulator produces smooth readings (daily cycle + small noise, pressure random walk, slow battery drain); real events only come from injected anomalies (`SDP_SIM_ANOMALIES`). Thresholds must therefore be tuned on `sdp-dev` data to give "a few anomalies per day".
- `sdp.metric_rollups_hourly` (M6) holds per device, metric and UTC hour: min, max, sum, count, first and last; raw measurements are partitioned by day.
- `Microsoft-Decision-1` (Preview) is called at `<account endpoint>/providers/microsoft/v1/systemone` with `{model, state, questions}`; a `choice` question returns the selected option and a probability per option; Entra ID authentication on the Cognitive Services scope. DataZoneStandard quota in the subscription: 2,160 units (60 used).
- Container Apps jobs get their image from a script (like the migrator jobs: `az acr build`, content-hash tag), not from `azd deploy`.

## Goals / Non-Goals

**Goals:** a small, explainable pipeline whose every step is visible (statistics, probabilities, threshold, explanation); tunable without code changes; safe to switch off per environment.

**Non-Goals:** multi-device or site-level anomalies; learning from reviews (verdicts are recorded for later calibration only); notifications; real-time streaming detection.

## Decisions

### D1. `agents/anomaly-detector`: a scheduled Python job
Python 3.13, uv, ruff, pytest (same conventions as the agent). Container Apps **job**, cron `*/5 * * * *`, parallelism 1, replica timeout 4 min, no retry (the next cycle retries). Entry point runs one cycle and exits. Image built in ACR by `scripts/build-anomaly-detector-image.sh` (content-hash tag, skipped when unchanged) from the post-provision hook, like the migrator. Libraries: `psycopg` (PostgreSQL with an Entra token as password), `httpx` (decision API), `openai` (Responses API to `gpt-6-luna`, Entra token provider), `azure-identity`, `azure-monitor-opentelemetry`, `numpy`-free statistics (standard library is enough for these sizes).

### D2. Candidate detection
For each device of the analysed sites (`ANOMALY_SITES`) and each analysed metric (`ANOMALY_METRICS`, default `temperature,humidity,pressure,co2,illuminance`):

- **Baseline** from the hourly rollups of the previous 7 days: *usual value* at time *t* = mean of the hourly averages at the same UTC hour over those days, interpolated linearly between the surrounding hours (taken at mid-hour) so that a steep daily slope does not look like a deviation at the end of each hour; *spread* = 1.4826 × median absolute deviation of all hourly averages from their same-hour usual value, floored per metric (e.g. 0.2 °C, 1 % RH, 0.5 hPa) so that very stable devices are not over-sensitive. A device with less than 3 full days of hourly rollups (hours ÷ 24) is skipped.
- **Window**: the readings of the last 15 minutes (raw measurements).
- **Deviation**: score *z* = (window mean − usual) / spread; candidate when |z| ≥ `ANOMALY_DEVIATION`.
- **Flat**: at least 10 readings, all identical, while the baseline's hourly min/max show the metric usually varies **at that hour of day** (median hourly range > 0; illuminance is legitimately 0 all night) → sensor-fault-like candidate.
- **Jump**: a step between consecutive readings ≥ `ANOMALY_DEVIATION` × spread.
- **Episodes**: if the device and metric have an anomaly whose period ended less than 15 minutes ago, the candidate extends it (end time, extremes) instead of creating a new one; its classification is kept.

Alternatives: z-score on raw readings without time-of-day profile — fires every afternoon on the daily cycle; seasonal decomposition or ML models — unnecessary for a demonstrator and harder to explain.

### D3. Classification by Microsoft-Decision-1
One `choice` question per new candidate. `state` is a compact JSON document: device (id, name, site, payload format), metric and unit, kind of deviation, window readings summary (count, min, max, mean, last, first/last time), usual value and spread at that hour, score, the same metric's scores of the other devices of the site over the same window (to tell a site-wide event from a single sensor), and the device's open anomalies. Options and criteria:

| Option | Criterion given to the model |
|---|---|
| `real_event` | The environment around the device really changed (e.g. door open, cooling failure); readings are consistent with physics |
| `sensor_fault` | The sensor or device misbehaves (stuck value, impossible jump, value out of physical range, isolated from neighbours) |
| `expected_pattern` | Normal behaviour for this device given its usual daily variation |
| `cannot_tell` | The data is insufficient to decide |

The response gives the selected option, a `confidence` and a probability per option; `confidence` is not the selected option's probability (measured 0.904 vs 0.928), so the status uses the probabilities, which are stored. Endpoint: `https://<account>.cognitiveservices.azure.com/providers/microsoft/v1/systemone` (the `services.ai.azure.com` host answers too); local (key) authentication is disabled on the account.

Status: highest option `real_event`/`sensor_fault` with p ≥ `ANOMALY_CONFIDENCE` → **confirmed**; `expected_pattern` with p ≥ confidence → **dismissed**; otherwise **to review**. The decision client is a small interface (`classify(state) -> probabilities`) so another scorer can replace the preview model (ADR-0011).

Failure handling: a candidate whose classification failed is stored as **pending** and re-submitted by the next cycle (still within the 10-minute target when the failure is transient); the cycle span records the error.

### D4. Explanation by gpt-6-luna
For confirmed anomalies only: Responses API, reasoning effort `low`, instructions "at most four sentences, only facts present in the data, name device, metric, values with units and time", input = the same `state` plus the classification and probabilities. Stored with the model deployment name. A failed explanation leaves the anomaly confirmed without explanation (retried by the next cycle).

### D5. Data model (migration V8)
`sdp.anomalies`: `id`, `device_id`, `site_id`, `metric`, `kind` (`deviation`, `flat`, `jump`), `started_at`, `ended_at`, observed `min/max/mean/last`, `usual_value`, `spread`, `score`, `decision` (`pending`, `confirmed`, `to_review`, `dismissed`), `classification`, `probabilities` (jsonb), `confidence_threshold`, `decision_model`, `decision_model_version`, `explanation`, `explanation_model`, `detected_at`, `updated_at`, `reviewed_at`, `reviewed_by`, `review_verdict` (`correct`, `incorrect`), `review_comment`. Indexes on `(site_id, started_at desc)` and `(device_id, started_at desc)`. The API status is `reviewed` when `reviewed_at` is set, otherwise `decision`.
Roles: new `sdp_insights` (select measurements, rollups, devices, sites, metric definitions; insert/update anomalies); `sdp_catalog` (device-api) selects anomalies and updates the review columns only. Retention: the maintenance job deletes anomalies older than the measurement retention (30 days).

### D6. Identity and infrastructure (Terraform)
New user-assigned identity `id-anomaly-detector` with: PostgreSQL principal (migrator principals list, group role `sdp_insights`), **Foundry User** on the Foundry account, Monitoring Metrics Publisher. The decision API requires the data action `Microsoft.CognitiveServices/accounts/AIServices/providers/action` (named by its `PermissionDenied` error), which `Cognitive Services OpenAI User` does not grant; Foundry User covers both models with one built-in role. `Microsoft-Decision-1` deployment named `decision-1` (deployment names must not contain the reserved word "Microsoft"; requests name the deployment) (`format = Microsoft`, version `1`, SKU DataZoneStandard, capacity `DECISION_MODEL_CAPACITY`, default 30) and the job are created only when `ANOMALY_DETECTION_ENABLED` is not `false`. `scripts/check-model-quota.sh` also checks the decision model when enabled and tells how to disable the feature when it is not available.

### D7. API (device-api)
`GET /api/v1/anomalies?siteId&deviceId&status&from&to&limit` (Read; default status confirmed + to review, last 7 days, newest first, limit ≤ 200), `GET /api/v1/devices/{deviceId}/anomalies?from&to&status` (Read), `POST /api/v1/anomalies/{id}/review` (Write; `{verdict, comment?}`; reviewer from the token; re-review overwrites). Validation errors as "field + message" problem details. OpenAPI regenerated; API Management imports it.

### D8. Portal
`config.json` gains `anomaliesEnabled` and `anomalySites` (from the azd settings). Device page: *Anomalies* card for the selected period (status badges "Real event · 91 %", "To review", values, explanation, review); "not analysed for this site" when the site is not in `anomalySites`. Navigation: *Anomalies* page with site and status filters, links to the device page over the anomaly's period, review dialog for Editors (verdict + comment).

### D9. Assistant
`list_anomalies(site_id?, device_id?, status?, from_time?, to_time?)` read-only tool calling the API above with the user's token, registered when `SDP_ANOMALIES_ENABLED` is true; results compacted like the other tools, citations to the device and period.

### D10. Observability
Azure Monitor OpenTelemetry distro (workload identity). Span `sdp.anomaly.cycle` (devices, candidates, by status, errors), child spans per decision call (`sdp.anomaly.classify`, with option probabilities) and explanation (`sdp.anomaly.explain`, with `gen_ai.usage.*`), httpx instrumentation for the HTTP calls; role name `sdp-anomaly-detector`.

### D11. Settings and tuning
azd settings → Terraform → job environment: `ANOMALY_DETECTION_ENABLED` (default on), `ANOMALY_SITES` (`paris-01`), `ANOMALY_DEVIATION`, `ANOMALY_CONFIDENCE`, `DECISION_MODEL_CAPACITY` (30). The defaults of the deviation and the confidence are chosen during implementation with a **dry run** of the detector over the last 7 days of `sdp-dev` (counts of candidates and confirmed anomalies per day for several values), aiming at a few confirmed anomalies per day on `paris-01`; the chosen values and the measured counts are recorded here and in the detector's README.

## Risks / Trade-offs

- [Smooth simulated data gives few natural anomalies] → tuned thresholds plus `SDP_SIM_ANOMALIES` for demos (documented in the demo guide).
- [Decision-1 is in Preview; calibration unknown on our data] → thresholds are settings; decisions keep all probabilities; reviews give labelled data; the scorer is behind an interface.
- [A rollup gap (watermark lag) makes the baseline slightly stale] → 7-day baseline is insensitive to the last hour missing.
- [Cost of model calls] → one decision call per new candidate, one LLM call per confirmed anomaly; capacity bounded by `DECISION_MODEL_CAPACITY`.
- [Job overlap if a cycle runs long] → parallelism 1 and a 4-minute timeout; episodes make re-processing idempotent.

## Migration Plan

Additive: `azd provision --preview` (Decision-1 deployment, identity, roles, job, API operations), `azd provision` (migration V8, image build), `azd deploy device-api device-analyst web`. Rollback: `ANOMALY_DETECTION_ENABLED=false` and provision (removes the job and the deployment; data kept).
