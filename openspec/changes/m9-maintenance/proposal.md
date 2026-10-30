# Proposal

## Why

Devices stop reporting when their battery dies or their radio degrades, and today the platform only notices once they are silent. [PRD-009](../../../docs/prd/PRD-009-m9-maintenance.md) asks for battery forecasts and a maintenance queue sorted by the probability of an intervention within 7 days, following [ADR-0011](../../../docs/adr/ADR-0011-model-based-analysis.md): statistics compute the signals, `Microsoft-Decision-1` gives the probability, `gpt-6-luna` drafts the work order, every decision is recorded.

## What Changes

- **Maintenance job**: third entry point (`maintenance`) of the model-based analysis project `agents/anomaly-detector` (same image and identity), hourly, for the configured sites (`MAINTENANCE_SITES`, default all):
  - **battery forecast** per device reporting `battery_voltage` or `battery_level`: piecewise trend of hourly averages over the last 14 days, weighting the recent days more, restarting after the last battery replacement (a jump up), and days left before the cut-off (2.5 V or 10 %);
  - **reporting signals**: silence, gaps between messages over the last 24 hours compared with the previous week, rejected messages;
  - **intervention probability** within 7 days from `Microsoft-Decision-1` (one yes/no question per device);
  - **queue item** when the probability reaches `MAINTENANCE_CONFIDENCE` (default 0.5), with a **work order** drafted by `gpt-6-luna` from the signals; Editors mark items planned or done (a done device is not queued again for 7 days).
- **Simulator**: battery computed from the time and the device seed (continuous across restarts); configured **aging devices** (`SDP_SIM_AGING_DEVICES`, one per demo site by default) drain to their cut-off in about two weeks, then get a new battery.
- **Data**: migration V10 — `sdp.device_maintenance` (latest assessment per device) and `sdp.maintenance_items` (queue and its history); grants for `sdp_insights` and `sdp_catalog`.
- **Platform API** (device-api): queue list, a device's assessment, and status changes (Write).
- **Portal**: *Maintenance* page (queue with filters, planned/done for Editors), battery forecast on the device page.
- **Site digest**: the figures gain the site's maintenance queue (count and most urgent devices).
- **Assistant**: `list_maintenance` tool.
- **Infrastructure**: maintenance job, settings `MAINTENANCE_ENABLED`, `MAINTENANCE_SITES`, `MAINTENANCE_CONFIDENCE`, simulator setting `SIMULATOR_AGING_DEVICES`; the `Microsoft-Decision-1` deployment exists when anomaly detection or maintenance is enabled.
- **GitHub Copilot cloud agent**: setup steps and OpenSpec agent so that the cloud agent can implement and test the offline tasks; tasks are marked *cloud agent* or *Azure*.

## Capabilities

### New Capabilities

- `predictive-maintenance`: battery forecasts, reporting signals, intervention probability, maintenance queue with work orders and Editor status, API, availability and observability.

### Modified Capabilities

- `device-simulation`: batteries continuous across restarts; aging devices.
- `site-digest`: the figures include the site's maintenance queue.
- `telemetry-visualization`: the device page shows the battery forecast.
- `web-portal`: Maintenance page.
- `device-analyst-agent`: the assistant can list the maintenance queue.

## Impact

- New: `maintenance` module and entry point in `agents/anomaly-detector`; migration V10; device-api `maintenance` package and OpenAPI operations; portal page and device-page line; agent tool; `.github/workflows/copilot-setup-steps.yml` and `.github/agents/openspec.agent.md`.
- Changed: simulator reading model (battery), digest figures, Terraform (job, settings, Decision-1 condition), build script (third job).
- Cost: one decision call per device per hour (30 devices in the demo, well within the deployment's capacity) and one LLM call per new queue item.
