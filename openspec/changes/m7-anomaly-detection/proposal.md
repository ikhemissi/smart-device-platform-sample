# Proposal: M7 — Anomaly detection

## Why

[PRD-007](../../../docs/prd/PRD-007-m7-anomaly-detection.md): M5 alerting only catches what someone configured a threshold for. The platform already holds weeks of history per device and two AI models (M6), so it can notice unusual readings by itself, say whether they look like a real event, a sensor fault or an expected pattern — with a calibrated confidence from `Microsoft-Decision-1` — and explain the confident ones in plain language. This is the first scenario of the "smarter platform" brainstorm ([docs/ideas](../../../docs/ideas/smart-platform.md)), following [ADR-0011](../../../docs/adr/ADR-0011-model-based-analysis.md).

## What Changes

- **Detection job** (`agents/anomaly-detector`, Python, Container Apps job every 5 minutes) for the devices of the configured sites (`ANOMALY_SITES`, default `paris-01`): compares each device's recent readings with its own usual values at that time of day (from the hourly rollups) and finds candidates — readings far from the baseline, flat lines and sudden jumps.
- **Triage by Microsoft-Decision-1**: each candidate is classified as *real event*, *sensor fault*, *expected pattern* or *cannot tell*, with a probability per class. Confident real events and sensor faults are **confirmed**; the others are **to review** (or recorded as expected).
- **Explanations by gpt-6-luna** for confirmed anomalies (a few sentences, from the data used for the decision).
- **Decision record** (PostgreSQL): candidate statistics, all probabilities, model deployment and version, threshold, classification, explanation, review.
- **Platform API** (device-api, Read; review is Write): list anomalies (by site, device, status, period), a device's anomalies, and mark an anomaly reviewed with a verdict.
- **Portal**: an *Anomalies* card on the device page and an *Anomalies* page (filters: site, status), Editors can review; shown when the feature is enabled.
- **AI assistant**: a read-only `list_anomalies` tool.
- **Infrastructure**: `Microsoft-Decision-1` deployment (DataZoneStandard) on the environment's Foundry account, the job and its identity; the feature can be switched off per environment (`ANOMALY_DETECTION_ENABLED`), and the pre-provision check verifies the decision model's availability.

## Capabilities

### New Capabilities
- `anomaly-detection`: detection of unusual readings on the configured sites, classification with calibrated confidence, explanations, decision records, review, and per-environment availability.

### Modified Capabilities
- `telemetry-visualization`: the device page shows the device's anomalies.
- `web-portal`: an Anomalies page with filters and review.
- `device-analyst-agent`: the assistant can list and explain anomalies.
- `device-simulation`: pressure drift stays continuous when the simulator restarts (anomaly injections restart it; the jumps were detected as anomalies).

## Impact

- New `agents/anomaly-detector` (Python job), azd service and CI job.
- Migration V8 (anomalies table, `sdp_insights` role), device-api endpoints and OpenAPI document (imported by API Management), portal pages, agent tool.
- Terraform: Decision-1 deployment, job, identity and roles; azd settings `ANOMALY_DETECTION_ENABLED`, `ANOMALY_SITES`, `ANOMALY_CONFIDENCE`, `DECISION_MODEL_CAPACITY`.
- Cost: one decision-model call per candidate and one LLM call per confirmed anomaly (a few per day on the demo fleet).
