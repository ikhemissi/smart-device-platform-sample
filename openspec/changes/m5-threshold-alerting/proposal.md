# Proposal: M5 — Threshold alerting

## Why

Milestone M5 ([PRD-005](../../../docs/prd/PRD-005-m5-threshold-alerting.md)) turns stored data into timely action: people are emailed when a monitored device's reading leaves its expected range, without being flooded, and a history of alerts is kept. It comes just before the AI agent so the agent can also reason about alerts.

## What Changes

- The decoder publishes every persisted measurement to a Service Bus topic; a filtered subscription selects the monitored subset of devices (by site or device id) — ADR-0009.
- A new alert service consumes the subscription, evaluates per-device thresholds, manages alert lifecycle (open, reminder, resolve) and sends emails with Azure Communication Services.
- Threshold management (CRUD) and alert history endpoints in the device API; thresholds and alert history on the portal device page.
- Simulator anomaly injection is used to demonstrate alerts.

## Capabilities

### New Capabilities

- `threshold-alerting`: Measurement publication for alerting, monitored-device selection, threshold management, alert lifecycle and notifications, alert history, and the related portal experience.

### Modified Capabilities

_None at requirement level. Publication is specified in `threshold-alerting`; it does not change the observable ingestion behaviour defined in `telemetry-ingestion`, except that a batch is acknowledged only after publication (consistent with "persisted then acknowledged")._

## Impact

- **Code**: decoder publisher; new `services/alert-service`; device-api threshold/alert endpoints; Flyway migration (`alert_thresholds`, `alert_threshold_state`, `alert_events`, `alert_notifications`); portal device page sections.
- **Azure resources**: Service Bus namespace (Standard, local auth disabled) with topic and subscription; Azure Communication Services + Email Communication Service with Azure-managed domain (data location Europe); alert-service container app and identity.
- **ADRs relied on**: ADR-0005, ADR-0009; implementation guidelines (alerting).
