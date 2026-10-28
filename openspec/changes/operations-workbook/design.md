# Design: Operations workbook

## Context

All workloads export OpenTelemetry to the environment's workspace-based Application Insights (implementation guidelines §5): requests, dependencies (including the decoder's per-message `sdp.ingest.message` spans with `sdp.device.id`), exceptions, logs and the domain metrics (`sdp.ingestion.*`, `sdp.cache.*`, `sdp.alerts.*`) in `customMetrics`. The Java agent exports metrics pre-aggregated per interval (sum, count, min, max), not as raw values. Azure resources (PostgreSQL flexible server, IoT Hub, Event Hubs, Service Bus, API Management) publish platform metrics to Azure Monitor without any diagnostic setting. The infrastructure is Terraform deployed with azd (ADR-0008). See [proposal.md](proposal.md) for motivation.

## Goals / Non-Goals

**Goals:** one workbook per environment, created and updated by `azd provision`, opening with no parameter to fill in.

**Non-Goals:** Azure Monitor alert rules, Grafana dashboards, per-device drill-down pages (the portal and Transaction search cover them), cost reporting, the AI agent (M6 adds its own indicators later).

## Decisions

### D1. Workbook as Terraform resource built in HCL
`azurerm_application_insights_workbook` in a small `infra/modules/monitoring` module, with `source_id` = the Application Insights resource (so it is listed under the component's Workbooks and the resource group). The workbook definition is built in HCL and serialized with `jsonencode()`, so it is always valid JSON with no placeholder to resolve; resource ids (Application Insights, PostgreSQL server, IoT Hub, Event Hubs namespace, Service Bus namespace, API Management) are plain variables. Each KQL query is a file under `queries/` with no time filter (the workbook's time range applies), so `scripts/check-workbook-queries.sh` runs exactly the workbook's queries against the environment for 24 hours and 7 days. Terraform preconditions, evaluated by `plan` / `azd provision --preview` before anything is provisioned, check that every query and metric chart follows the `TimeRange` parameter and that every `.kql` file is shown. The workbook name is a deterministic UUID (`uuidv5` of the environment name), so re-provisioning updates the same workbook. Alternatives: a hand-written JSON template rendered with `templatefile()` (harder to review and to validate), a gallery template imported by hand (manual, drifts), `azapi` (not needed, `azurerm` supports workbooks).

### D2. Data sources per section
- **Key indicators**: KQL on Application Insights.
  - devices reporting = distinct `sdp.device.id` of `sdp.ingest.message` spans;
  - measurements stored and rejected = `sdp.ingestion.messages` by `sdp.ingestion.outcome` and reason;
  - ingestion latency = `sdp.ingestion.latency` average (sum/count) and maximum, since percentiles are not available from pre-aggregated metrics;
  - API = `requests` of the platform services and APIM (count, failure rate, 95th percentile duration);
  - cache hit ratio = `sdp.cache.lookups` by outcome;
  - alerting = `sdp.alerts.opened/closed` and `sdp.alerts.notifications` by outcome.

  Shown as tiles, plus a time chart of measurements per 5 minutes.
- **Latest errors**: `union exceptions, requests (success == false), dependencies (success == false)` of the platform services, newest first, limited to 50 rows, with role, type, message and `operation_Id`. A second grid shows rejected messages by reason over time.
- **Critical components**: workbook metric items (Azure Monitor metrics provider) bound to each resource id:
  - PostgreSQL `cpu_percent`, `memory_percent`, `storage_percent`, `active_connections`;
  - IoT Hub `connectedDeviceCount`, `d2c.telemetry.ingress.success`, `d2c.telemetry.ingress.sendThrottle`;
  - Event Hubs `IncomingMessages`, `OutgoingMessages`, `ThrottledRequests`, `ServerErrors`;
  - Service Bus `ActiveMessages`, `DeadletteredMessages`;
  - API Management `Requests` and `Duration`.

  Metric names are checked against each resource's metric definitions during implementation.

Alternative considered: an Azure Portal dashboard. Rejected because workbooks support parameters, KQL grids and metric charts on one page and are the documented way to share monitoring views.

### D3. Time range and access
A global `TimeRange` parameter (default last 24 hours, options 1 h to 30 days) drives every query and metric item. Reading the workbook needs read access to the resource group and the Application Insights data (e.g. *Monitoring Reader*); no new role assignment is created. The deployer, as Owner, already has it. An azd output `OPERATIONS_WORKBOOK_URL` gives the portal link.

## Risks / Trade-offs

- [Metric or attribute names change in a service] → the workbook is checked by running each KQL query against the environment (tasks), and the queries only use names documented in the guidelines and service READMEs.
- [Large JSON template hard to review] → the template is generated from a readable source layout (sections in order, one item per query) and validated as JSON with no unresolved placeholder before provisioning.
- [Sampling would undercount distinct devices] → the agent does not sample in this demonstrator (default 100%); the indicator is labelled as seen in telemetry.

## Migration Plan

Additive: `azd provision` creates the workbook; removing the module deletes it. No data migration.
