# Proposal: Operations workbook

## Why

The platform is a customer demonstrator: [PRD-000](../../../docs/prd/PRD-000-product-overview.md) asks that every data flow can be followed in the monitoring tooling to "show the plumbing". Today the telemetry is all in Application Insights and Azure Monitor (domain metrics, traces, resource metrics), but showing it means writing KQL queries or opening each resource's metrics blade during the demo. One Azure Monitor workbook gives the customer a single page with the platform's key indicators, its latest errors, and the health of its most critical components, and it stays useful in later milestones.

## What Changes

- A new Azure Monitor workbook "Smart Device Platform — operations", deployed with the environment by Terraform (no manual portal step), with a time range picker and these sections:
  - **Key indicators**: devices reporting, measurements ingested and rejected, end-to-end ingestion latency, API traffic, failure rate and latency, history cache hit ratio, alerts opened/closed and emails sent/failed.
  - **Latest errors**: most recent exceptions, failed requests and failed dependencies across all services (with operation id to open the end-to-end transaction), and rejected device messages by reason.
  - **Critical components**: Azure Monitor metric charts of PostgreSQL (CPU, memory, storage, connections), IoT Hub (connected devices, telemetry messages, throttling), Event Hubs (incoming/outgoing messages, throttled requests, server errors), plus Service Bus (active and dead-lettered messages) and API Management (requests, failed requests, duration).
- The workbook's resources are bound by Terraform to the environment's resources; it opens from the resource group or Application Insights in the Azure portal.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `platform-observability`: adds requirements for an operations workbook deployed with the environment: key indicators, latest errors and critical component metrics.

## Impact

- **Infrastructure**: new `azurerm_application_insights_workbook` in Terraform with its JSON definition under `infra/`; resource ids of PostgreSQL, IoT Hub, Event Hubs, Service Bus and API Management passed to it (a PostgreSQL server id output is added). No new data collection, cost or permissions: it reads existing Application Insights telemetry and platform metrics.
- **Documentation**: observability guidelines and the demo guides point to the workbook.
- **ADRs relied on**: ADR-0008 (infrastructure as code with Terraform); implementation guidelines §5 (observability).
