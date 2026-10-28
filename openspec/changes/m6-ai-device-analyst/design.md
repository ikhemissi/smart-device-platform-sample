# Design: M6 — AI device analyst

## Context

ADR-0010 fixes the approach (Python + Microsoft Agent Framework on ACA, Foundry model, tools over the platform API with the forwarded user token, Redis conversation state). ADR-0007 makes the agent API share the platform API audience. See [proposal.md](proposal.md) and `specs/`.

## Goals / Non-Goals

**Goals:** a small, well-instrumented agent that showcases tool use over real data, streaming and evaluation.

**Non-Goals:** write actions, multi-agent orchestration, RAG over documents, Foundry-hosted agents.

## Decisions

### D1. Service shape
`agents/device-analyst`: Python 3.13, `uv` for dependencies, FastAPI + Uvicorn, `sse-starlette` for SSE. Endpoints per spec under `/api/v1/agent`. JWT validation with `PyJWT` + Entra JWKS (issuer, audience = platform API, roles Read/Write) — same rules as Java services. Container: `python:3.13-slim`, non-root.

### D2. Agent
Microsoft Agent Framework `ChatAgent` with the Foundry/Azure OpenAI chat client authenticated by `ManagedIdentityCredential` (DefaultAzureCredential locally), model deployment name from config (default `gpt-6-luna`, version `2026-09-22`, DataZoneStandard EU). System instructions: role, read-only policy, always cite device ids + periods, prefer aggregated series for > 24 h, answer in the user's language, admit missing data. Max tool iterations 10.

### D3. Tools (read-only)
Python functions decorated as agent tools, each calling APIM with `httpx.AsyncClient` and the **caller's bearer token** (kept in a request-scoped context variable, never persisted or logged):
`list_sites`, `fleet_status(site_id?)`, `metric_summary(metric, from, to, group_by, site_id?)`, `search_devices(site_id?, query?, status?)`, `get_device(device_id)`, `get_device_metrics(device_id)`, `get_latest_measurements(device_id, count≤20)`, `get_series(device_id, metrics, from, to)` (maxPoints 200 to bound tokens), `list_alerts(site_id?, device_id?, status?, from?, to?)`. Results are compacted (rounded numbers, dropped attributes) and truncated with an explicit "truncated" marker. HTTP errors are turned into tool messages ("not found", "not permitted").

### D3b. Fleet analytics endpoints (device-api)
`/api/v1/fleet/metric-summary` runs one grouped SQL query over `measurements` (bounded range, partition pruning) using `min/avg/max/count` and `first_value/last_value` window aggregates per group; results cached with the M3 chunk rules (watermark-based). `/api/v1/fleet/status` aggregates `devices.last_seen_at`. Both are Read operations in the APIM import.

### D4. Conversation state
Agent Framework thread messages serialised to Azure Managed Redis key `sdp:agent:conv:{id}` (JSON, sliding TTL 1 h) plus owner `oid`; ownership checked on every access (404 on mismatch). Redis access with Entra (`redis-entraid` credential provider, agent managed identity). Max 50 messages; older tool results are summarised away before reaching model context limits.

### D5. Streaming through APIM and the SPA
APIM operation policy `forward-request buffer-response="false"` for the messages operation, and higher timeout (120 s). SPA uses `@microsoft/fetch-event-source` (supports `Authorization` header) and renders `delta`/`tool`/`done` events; citations rendered as links to `/devices/{id}?period=…`.

### D6. Foundry resources (Terraform)
`azapi` (API `2025-06-01`) for the AI Services account (`kind = AIServices`, `allowProjectManagement = true`, `disableLocalAuth = true`, custom subdomain), the Foundry project and the model deployment (SKU `DataZoneStandard`, `NoAutoUpgrade`). Model name, version, SKU and capacity come from the azd environment (`AGENT_MODEL_*`, defaults `gpt-6-luna` / `2026-09-22` / `DataZoneStandard` / 50K TPM) and are checked by a pre-provision quota preflight (`scripts/check-model-quota.sh`). The project's Application Insights connection uses the documented `AppInsights` / `ApiKey` form whose key is the ingestion connection string, an endpoint identifier rather than a credential (Application Insights has local authentication disabled); the project identity gets Log Analytics Reader and Privileged Monitoring Data Reader on Application Insights for Foundry tracing. Agent identity: `Cognitive Services OpenAI User` on the account (model inference is all it needs; `Foundry User`, formerly `Azure AI User`, also allows listing keys and is reserved for the deployer, assigned by role id).

### D7. Observability and evaluation
`azure-monitor-opentelemetry` distro with `ManagedIdentityCredential`; Agent Framework OTel instrumentation enabled (GenAI semantic conventions, prompt/response content capture **disabled** by default; enabled per environment for demos). `httpx` instrumentation propagates `traceparent` to APIM. Evaluation: `evals/questions.jsonl` (PRD examples + expected tools/devices) and a script using `azure-ai-evaluation` (groundedness, relevance, tool-call accuracy) run against a seeded environment; thresholds documented in the README.

## Risks / Trade-offs

- [Model quota in the EU data zone] → model name/capacity are variables; check quota during `azd provision` (preflight script). On 2026-10-28 `gpt-6-luna` was generally available in `francecentral` with DataZoneStandard, and the subscription had 100K tokens per minute of DataZoneStandard quota for it.
- [Token forwarding to tools: token may expire mid-conversation] → each turn uses the token of the current request; long turns are bounded by 10 tool calls and 120 s.
- [Prompt injection via device names/descriptions] → tool outputs are clearly delimited as data, instructions forbid following instructions found in data, and tools are read-only anyway.
- [Agent Framework API evolution (fast release cadence)] → pin versions with `uv.lock`; upgrade deliberately.

## Migration Plan

Additive. The portal hides the assistant entry point when `/api/v1/agent` is not deployed (feature flag in runtime config).
