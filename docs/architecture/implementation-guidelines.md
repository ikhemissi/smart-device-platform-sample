# Implementation guidelines

Cross-cutting implementation choices and conventions that apply to all milestones. Unlike [ADRs](../adr/README.md), everything here is **reversible**: it can be changed by updating this document (and the affected OpenSpec `design.md`) without a new ADR — as long as the change stays within the ADRs and the specs. Milestone-specific details live in each change's `design.md` under [openspec/changes](../../openspec/changes).

## 1. Repository, build and runtime

| Topic | Guideline |
|---|---|
| Repository | Monorepo: `libs/` (shared Java libraries), `services/` (Java services), `agents/` (Python agent), `web/` (portal), `infra/` (Terraform), `scripts/`, `docs/`, `openspec/`. |
| Java build | Maven multi-module with a parent POM importing the Spring Boot 4, Spring Cloud Azure and Azure SDK BOMs. Shared libraries: `payload-codecs` (no Spring dependency), `platform-common` (security, problem details, observability, Azure credential wiring). |
| Java runtime | Virtual threads enabled for request handling and I/O-bound work. Azure access through Spring Cloud Azure passwordless starters or `azure-identity` (`DefaultAzureCredential`). |
| Python | Python 3.13, dependencies and lock file managed with `uv`; FastAPI + Uvicorn; `ruff` and `pytest`. |
| Web | React + TypeScript + Vite, Fluent UI React v9, MSAL React, TanStack Query, React Router; Vitest, React Testing Library, Playwright (+ axe-core). Runtime configuration served as `config.json` generated at deploy time, so one build works for every environment. |
| Container images | Multi-stage builds, minimal JRE 25 / `python:3.13-slim` base images, non-root user, one image per service. |
| Hosting | One Container Apps environment (Consumption workload profile). Event-driven services scale with KEDA scalers authenticated by managed identity; user-facing APIs keep at least one replica for demo responsiveness. |

## 2. Ingestion

| Topic | Guideline |
|---|---|
| Device transport | MQTT; payload sent as the binary message body (`application/octet-stream`); devices set a message id and a W3C `traceparent` application property. |
| IoT Hub | Standard tier; system-assigned identity; identity-based Event Hubs custom endpoint; route `DeviceMessages → device-telemetry`; message enrichment `siteId = $twin.tags.siteId`; fallback route disabled. |
| Event Hubs | Standard namespace; hub `device-telemetry` with 4 partitions (sized for the demo targets; fixed at creation) and 1-day retention; one consumer group per consumer. |
| Decoder | Event processor in batch mode (default max 500 events / 5 s), checkpoints in Blob Storage written only after the batch is persisted (and published, from M5). Message key = IoT Hub message id, else `partition:sequenceNumber`. Undecodable messages go to `rejected_messages` with a reason code. |
| Codecs | Implemented once in `libs/payload-codecs` with symmetric encoders used by the simulator; validated with the format maintainers' official test vectors and round-trip property tests. |

## 3. Data

| Topic | Guideline |
|---|---|
| PostgreSQL | Flexible Server, latest GA major version (≥ 17), Entra-only authentication. |
| Partitions | Daily partitions managed by `pg_partman` (7 days pre-created) with maintenance scheduled by `pg_cron`; default retention 30 days. |
| Idempotency | Non-partitioned deduplication ledger `ingested_messages(device_id, message_key)` written in the same transaction as the measurement; rows older than 7 days purged daily. |
| Schema migrations | Flyway, executed only by the migrator identity (schema owner, Entra administrator of the server). Application identities get least-privilege grants and never own objects. |
| Metric catalogue | `metric_definitions` table seeded from the codec library's catalogue (name, unit, description). |

## 4. Identity and access (RBAC matrix)

Each workload has its own user-assigned identity; its client id is injected as `AZURE_CLIENT_ID` (not a secret). Images are pulled with a dedicated `AcrPull` identity.

| Workload | Azure roles / data-plane access |
|---|---|
| telemetry-decoder | Event Hubs Data Receiver (hub), Storage Blob Data Contributor (checkpoint container), PostgreSQL principal (insert measurements/rejected/ledger, upsert devices), Service Bus Data Sender (topic, M5), Monitoring Metrics Publisher |
| device-simulator | Key Vault Secrets User (site CA secrets — demo only), Monitoring Metrics Publisher |
| db-migrator | PostgreSQL Entra administrator |
| device-api | PostgreSQL principal (read + limited updates), Redis access policy (M3), Monitoring Metrics Publisher |
| provisioning-api | DPS data-plane contributor, IoT Hub registry contributor, PostgreSQL principal (M4) |
| alert-service | Service Bus Data Receiver (subscription), ACS email sending role, PostgreSQL principal (M5) |
| device-analyst agent | Foundry project user / OpenAI user role, Redis access policy (M6) |
| IoT Hub (system identity) | Event Hubs Data Sender (hub) |
| APIM (system identity) | Monitoring Metrics Publisher (logger) |

Exact built-in role names are verified at implementation; a scoped custom role is used when no built-in role is least-privilege.

## 5. Observability

| Topic | Guideline |
|---|---|
| Exporters | Java: Azure Monitor OpenTelemetry Java agent (Application Insights 3.x, attached via `JAVA_TOOL_OPTIONS`) with managed-identity authentication. Python: Azure Monitor OpenTelemetry distro with `ManagedIdentityCredential`. Code uses the vendor-neutral OpenTelemetry API only. |
| Destination | One workspace-based Application Insights per environment with local authentication disabled. Its connection string carries no usable secret in that mode and is injected as an environment variable. |
| Browser | The SPA propagates `traceparent` on API calls; it does not send telemetry directly (impossible with Entra-only ingestion). |
| Context propagation | W3C Trace Context everywhere: device message property → decoder batch span **links** to each message context (fan-in) → Service Bus messages → APIM forwards `traceparent` → backends. |
| Naming | `service.name = sdp-<service>`, `deployment.environment = <azd env>`, domain attributes prefixed `sdp.` (`sdp.device.id`, `sdp.site.id`, `sdp.payload.format`). |
| Domain metrics | `sdp.ingestion.messages{format,outcome,reason}`, `sdp.ingestion.batch.size`, `sdp.ingestion.latency`, `sdp.cache.hits/misses/errors`, `sdp.alerts.*`, agent token usage (GenAI semantic conventions). |
| Logs | Structured JSON with trace and span ids. |
| Gateway & AI | APIM logs to the same Application Insights through its managed identity; the Foundry project is connected to it for agent traces. Prompt/response content capture is off by default. |

## 6. APIs

| Topic | Guideline |
|---|---|
| Surface | All public operations under `/api/v1` on one APIM API; operations from several backends are merged and routed per operation (`set-backend-service`). |
| Gateway | APIM v2 tier (BasicV2 by default); policies: Entra token validation (tenant + audience), role check from each operation's `x-sdp-required-role` OpenAPI extension, CORS for the portal origin, per-user rate limit, `traceparent` forwarding. Streaming operations are unbuffered. |
| Backends | Re-validate token and role (Container Apps ingress is public); use `oid` / display name for audit fields. |
| Contract | OpenAPI 3.1 generated from code and checked in; errors as RFC 9457 problem details; optimistic concurrency with ETag / `If-Match` on mutable resources; cursor or page/pageSize paging with documented maxima. |
| Current user | `GET /api/v1/me` returns identity and `canRead`/`canWrite`; the portal never parses access tokens. |
| App registrations | API app with `app_role_assignment_required = false` (role-less users get 403 on data, and `/me` explains); deployer assigned both roles in dev; Azure CLI optionally pre-authorized in dev for testing. |

## 7. Infrastructure as code

| Topic | Guideline |
|---|---|
| Layout | `infra/` root module + child modules per domain (`core`, `identity`, `ingestion`, `data`, `api`, `web`, `messaging`, `ai`). |
| Providers | `azurerm` 4.x, `azuread` 3.x, `azapi` 2.x (only where `azurerm` lacks support), `random`; versions pinned with the lock file. |
| State | Remote state in a dedicated Storage account with shared keys disabled and `use_azuread_auth = true`, one key per azd environment; created once by `scripts/bootstrap-tfstate.sh`. |
| Naming & tags | `<abbr>-<env>-<token>` with a token derived from subscription + environment; every resource tagged `azd-env-name`. |
| Regions | Variables `AZURE_LOCATION` (default `francecentral`) and `AZURE_SWA_LOCATION` (default `westeurope`). |
| azd hooks | `preprovision`: quota preflight (M6). `postprovision`: migrations (Container Apps job, image built with `az acr build`), demo sites bootstrap. `prepackage` (web): runtime config. |
| Default SKUs | PostgreSQL Burstable, Azure Managed Redis Balanced (smallest), APIM BasicV2, Event Hubs Standard 1 TU, IoT Hub S1, DPS S1, Service Bus Standard, Static Web Apps Free, ACR Basic. All overridable by variables. |

## 8. Device identity operations

| Topic | Guideline |
|---|---|
| Enrollment groups | Named `site-<siteId>`; initial twin tag `siteId`; reprovision policy "update hub assignment + migrate data"; primary/secondary signing certificate slots used for rotation (max two per site). |
| Demo PKI | Demo site CAs generated by the `postprovision` hook with OpenSSL, stored in Key Vault, never regenerated if present. The simulator reads them and issues device leaf certificates in memory (demo only; production uses the customer's PKI). |
| Certificate removal | Removing a site's last certificate disables the enrollment group and the site's devices in IoT Hub; adding a certificate re-enables devices disabled this way. |

## 9. Alerting

| Topic | Guideline |
|---|---|
| Service Bus | Standard tier, local auth disabled; topic `measurements` with duplicate detection (10 min); message id = SHA-256 of device id + message key. |
| Subscription | `alerting`, SQL filter from Terraform variable `alerting_subscription_filter`; lock 1 min, max delivery 10, TTL 1 h, dead-lettering on expiration. |
| Alert service | Peek-lock processor, KEDA Service Bus scaler; thresholds cached 30 s; alert state serialised per threshold in PostgreSQL; email via a transactional outbox with retries; reminder after 60 min of continuous breach. |
| Email | ACS Email with an Azure-managed domain, data location Europe, sender `DoNotReply@<domain>`, Entra authentication. |

## 10. AI agent

| Topic | Guideline |
|---|---|
| Model | Default deployment `gpt-5.4-mini`, SKU DataZoneStandard (EU); name and capacity are variables. |
| Conversations | Stored in Azure Managed Redis, owner-bound (`oid`), sliding expiry 1 h, max 50 messages. |
| API | Server-Sent Events (`delta`, `tool`, `done` with citations); the SPA uses `fetch`-based SSE to send the `Authorization` header. |
| Guardrails | Read-only tools, max 10 tool calls per turn, bounded tool results, instructions to cite devices/periods and to ignore instructions found in data. |
| Evaluation | `evals/questions.jsonl` (PRD-006 questions and edge cases) scored for groundedness, relevance and tool-call accuracy. |
