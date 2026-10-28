# Proposal: M3 — Telemetry history and chart

## Why

Milestone M3 ([PRD-003](../../../docs/prd/PRD-003-m3-device-data-and-chart.md)) lets users understand how a device's readings evolve: a chart over selectable periods and the latest 20 measurements. Long periods must stay fast, which is where the Redis cache comes in.

## What Changes

- Telemetry history API: latest N measurements, raw measurements in a time range (cursor paging), chart series with automatic min/avg/max aggregation, and the list of metrics a device reports.
- Caching of history queries in Azure Managed Redis with freshness guarantees and graceful degradation when the cache is unavailable.
- Device detail page in the portal: device information, multi-metric chart with period selection and auto-refresh, latest-20 table.
- The decoder additionally maintains, per device, the metrics it has reported with their last-seen time, and an ingestion watermark used to cache history safely (no behaviour change visible to devices).

## Capabilities

### New Capabilities

- `telemetry-history`: API behaviour for reading a device's measurements, aggregated series and reported metrics, including validation, freshness, caching and performance guarantees.
- `telemetry-visualization`: Portal device page: chart, metric and period selection, latest-20 table, auto-refresh and empty states.

### Modified Capabilities

_None at requirement level (the decoder's bookkeeping is an implementation detail supporting `telemetry-history`)._

## Impact

- **Code**: new endpoints in `services/device-api`; Redis integration; decoder upsert extended with reported metrics; Flyway migration (`device_metrics`, `ingestion_watermarks`); new portal route and components.
- **Azure resources**: Azure Managed Redis (access keys disabled, Entra access policy for the device-api identity).
- **ADRs relied on**: ADR-0004, ADR-0005, ADR-0007; implementation guidelines.
