# Proposal: M6 — AI device analyst

## Why

Milestone M6 ([PRD-006](../../../docs/prd/PRD-006-m6-ai-analyst.md)) lets users ask questions in natural language about devices, measurements and alerts and receive answers grounded in the platform's real data — the culminating capability of the demonstrator.

## What Changes

- A Python agent service built with Microsoft Agent Framework, hosted on Azure Container Apps, using a Foundry model deployment (EU data zone), exposed through APIM with streaming responses.
- Fleet analytics endpoints in the device API (metric summary grouped by site or device, fleet status) so fleet-wide questions need one call instead of one per device.
- Read-only tools that call the platform API with the user's own access token (devices, sites, fleet summaries, latest measurements, series, metrics, alerts).
- Conversation management bound to the user, stored in Azure Managed Redis with a 1-hour sliding expiry.
- An assistant panel in the portal with streaming answers, tool activity, citations linking to device pages and device context.
- Tracing of agent runs (GenAI semantic conventions) in Application Insights and an evaluation dataset for quality gating.

## Capabilities

### New Capabilities

- `device-analyst-agent`: Conversational analysis of platform data: conversation API, grounding and citations, read-only behaviour, authorisation, limits, language, latency, observability/evaluation and the portal assistant panel.

- `fleet-analytics`: Fleet-level metric summaries and status overview across sites and devices.

### Modified Capabilities

_None._

## Impact

- **New code**: fleet endpoints in `services/device-api`; `agents/device-analyst` (Python 3.13, uv), portal assistant panel, APIM operations for `/api/v1/agent/*`, evaluation dataset under `agents/device-analyst/evals/`.
- **Azure resources**: Azure AI Services (Foundry) account + project + model deployment (DataZoneStandard), agent container app and identity, Redis access policy for the agent identity, Foundry project connection to Application Insights.
- **ADRs relied on**: ADR-0001, ADR-0005, ADR-0007, ADR-0010; implementation guidelines (AI agent).
