# Design: M1 — Device data ingestion

## Context

Greenfield repository (only docs and OpenSpec). See [proposal.md](proposal.md) for motivation and the specs under `specs/` for required behaviour. This design applies, without restating, ADR-0001 (runtime), ADR-0002 (pipeline), ADR-0003 (formats), ADR-0004 (data model), ADR-0005 (passwordless), ADR-0006 (device identity) and ADR-0008 (IaC, regions, networking) in [docs/adr](../../../docs/adr/README.md), and the cross-cutting conventions of the [implementation guidelines](../../../docs/architecture/implementation-guidelines.md) (observability, RBAC matrix, IaC conventions).

## Goals / Non-Goals

**Goals:**
- Establish the monorepo layout, build, container and IaC conventions every later milestone reuses.
- Deliver a working, observable ingestion path from simulated devices to PostgreSQL deployable with `azd up`.

**Non-Goals:**
- Any user-facing API, APIM, Entra app registrations or UI (M2+).
- Site certificate management API (M4) — M1 creates demo enrollment groups with tooling only.
- Service Bus publication (M5).

## Decisions

### D1. Repository layout

```
azure.yaml                     # azd project (infra.provider: terraform)
pom.xml                        # Maven parent: Java 25, Spring Boot 4 BOM, Spring Cloud Azure BOM
libs/payload-codecs/           # pure Java, no Spring: detection + codecs + encoders + metric catalogue
libs/platform-common/          # Spring auto-config: OTel attributes, JSON logging, Azure credential wiring
services/telemetry-decoder/
services/device-simulator/
services/db-migrator/          # Flyway migrations + runner (container app job)
infra/                         # Terraform root + modules/{core,identity,ingestion,data}
scripts/                       # bootstrap-tfstate.sh, bootstrap-demo-sites.sh, run-migrations.sh
docs/  openspec/
```
Later milestones add `services/device-api`, `services/provisioning-api`, `services/alert-service`, `agents/device-analyst` (Python) and `web/portal`.
*Alternative*: one repo per service — rejected; a demonstrator benefits from one clone, one `azd up`, shared codecs.

### D2. Payload codec library
`PayloadDecoder.decode(byte[]) → DecodeResult` (sealed: `Decoded(format, metrics, attributes)` | `Rejected(reason, detail)`), dispatching on byte 0 through a registry map (`0x05`, `0x03`, `0x40/0x44`, `0xCA`; `0x41/0x45` → `UNSUPPORTED_FORMAT`). Each codec has a symmetric encoder used by the simulator. Values are rounded to the format resolution using `BigDecimal` to make test-vector equality exact. Metric catalogue (name, unit, description) is an enum shared with the DB seed migration. Tests: JUnit 5 parameterised tests with official vectors (Ruuvi DF3/DF5, BTHome examples, Cayenne examples) plus jqwik property tests for round trips.

### D3. Decoder processing model
- Azure Event Hubs `EventProcessorClient` with `processEventBatch(handler, maxBatchSize=500, maxWait=5s)`, `BlobCheckpointStore` on a dedicated container, consumer group `telemetry-decoder`.
- Per batch: decode all events (CPU-bound, sequential — fast) → one JDBC transaction: multi-row `INSERT INTO ingested_messages … ON CONFLICT DO NOTHING RETURNING device_id, message_key` (deduplication ledger, implementation guidelines §3) → insert into `measurements`/`rejected_messages` only the returned (new) keys, then one aggregated device upsert (`INSERT … ON CONFLICT (device_id) DO UPDATE SET last_seen_at = GREATEST(…)`, `last_payload_format`) → commit → `updateCheckpoint(lastEvent)`.
- Inputs from IoT Hub/Event Hubs: device id from system property `iothub-connection-device-id`, measurement time from `iothub-enqueuedtime`, site from application property `siteId` (IoT Hub enrichment), message key = `message-id` system property or `<partition>:<sequenceNumber>`.
- Errors: DB exceptions → no checkpoint, exponential back-off (Resilience4j retry), processor continues from last checkpoint; decode errors → rejected rows.
- Health: Spring Boot actuator liveness/readiness on a management port (no ingress); readiness false while partitions cannot be claimed.
*Alternative*: Spring Cloud Stream binder for Event Hubs — rejected; less control over batch-level transaction/checkpoint ordering.

### D4. Database schema (Flyway `V1__…`)
- `sites(site_id text pk, display_name, created_at)`; `devices(device_id text pk, site_id fk, display_name, description, location jsonb, last_payload_format, first_seen_at, last_seen_at, updated_at, updated_by)`.
- `metric_definitions(name pk, unit, description)` seeded from the codec catalogue.
- `measurements(device_id, site_id, measured_at timestamptz, received_at, message_key, payload_format, raw_payload bytea, metrics jsonb, attributes jsonb, PRIMARY KEY (device_id, measured_at, message_key)) PARTITION BY RANGE (measured_at)`; same pattern for `rejected_messages` (PK `(received_at, device_id, message_key)`).
- `ingested_messages(device_id, message_key, ingested_at, PRIMARY KEY (device_id, message_key))` — non-partitioned deduplication ledger; `pg_cron` deletes rows older than 7 days daily (Event Hubs retention is 1 day, device resends happen within minutes).
- `pg_partman.create_parent(... '1 day', premake 7)`, `retention = '30 days'`, `retention_keep_table = false`; `pg_cron` runs `partman.run_maintenance_proc()` hourly. Server parameters `azure.extensions = PG_PARTMAN,PG_CRON`, `shared_preload_libraries` includes `pg_cron,pg_partman_bgw`, `cron.database_name = sdp` (Terraform).
- Roles: migrator MI is the server's Entra administrator and schema owner. Migration `R__principals.sql` (repeatable, placeholders) runs `pgaadauth_create_principal_with_oid` for each workload identity, then grants: decoder → `INSERT` on measurements/rejected_messages/ingested_messages, `INSERT, UPDATE(last_seen_at,last_payload_format)` + `SELECT` on devices, `SELECT` on sites. Application roles never own objects.

### D5. Migration execution
`db-migrator` is a Spring Boot + Flyway non-web app packaged as an image and run as a **Container Apps job** (manual trigger) under the migrator identity. The azd `postprovision` hook builds/pushes its image with `az acr build` (Entra auth, no local Docker needed), updates the job image, starts it and waits for success — so the schema exists before `azd deploy` starts the services. Locally, `scripts/run-migrations.sh` runs the same app with the developer's Entra token (developer added as additional Entra admin in dev only).
*Alternative*: Flyway at decoder start-up — rejected (decoder would need DDL rights, violating least privilege).

### D6. Device simulator
- Spring Boot non-web app; one virtual thread per device; Azure IoT device SDK (MQTT) with `SecurityProviderX509` + provisioning device client.
- Site CA PEM (cert + key) read from Key Vault secret `site-<siteId>-ca` with the simulator MI; device leaf certificates (CN = device id, 1-year validity) generated in memory with BouncyCastle at start-up.
- Reading model per device: seeded RNG (seed = hash(device id)) → stable per-device baselines; temperature = base + amplitude·sin(2π·t/24h) + noise; humidity anti-correlated; battery decay; counters increment.
- Configuration via Spring properties/env: `SIM_SITES`, `SIM_DEVICES_PER_SITE`, `SIM_FORMAT_MIX`, `SIM_INTERVAL`, `SIM_JITTER`, `SIM_CORRUPTION_RATE`, `SIM_ANOMALIES` (e.g. `paris-01-ruuvi5-001:temperature=12:5m`). On-demand anomalies = `az containerapp update --set-env-vars` (new revision) — documented in the demo guide. Deployed with `minReplicas = maxReplicas = 1`, no ingress.
*Alternative*: one MQTT connection per site via a simulated gateway — rejected; per-device X.509 identity is the point of ADR-0006.

### D7. Infrastructure (Terraform modules for M1)
- `core`: resource group, Log Analytics, Application Insights (`local_authentication_disabled = true`), ACR (admin disabled, anonymous pull disabled), ACA environment (Consumption), Key Vault (RBAC).
- `identity`: user-assigned identities `id-decoder`, `id-simulator`, `id-migrator`, `id-acrpull`; role assignments per the RBAC matrix in the implementation guidelines (ADR-0005).
- `ingestion`: Event Hubs namespace (Standard, `local_auth_enabled = false`), hub `device-telemetry` (4 partitions, 1-day retention), consumer group; Storage (shared key disabled) + checkpoint container; IoT Hub S1 (4 partitions) with system identity, identity-based Event Hubs endpoint, route `DeviceMessages → device-telemetry`, enrichment `siteId = $twin.tags.siteId`, fallback route disabled; DPS S1 linked to the hub (exception, ADR-0005).
- `data`: PostgreSQL Flexible Server (Burstable B1ms/B2s, latest major), `password_auth_enabled = false`, `active_directory_auth_enabled = true`, migrator MI as Entra admin, database `sdp`, firewall "allow Azure services".
- Container apps: decoder (KEDA Event Hubs scaler with managed identity, min 1, max 4), simulator; migrator job. Resources not yet in azurerm (identity-based KEDA auth if missing) via `azapi`.
- State: `scripts/bootstrap-tfstate.sh` creates a state storage account (shared key disabled); `infra/provider.conf.json` feeds azd env values to the `azurerm` backend with `use_azuread_auth = true`.

### D8. Demo bootstrap (azd `postprovision` hook, idempotent)
1. Run migrations (D5).
2. `scripts/bootstrap-demo-sites.sh`: for each site in `DEMO_SITES` — if Key Vault secret `site-<id>-ca` is absent, generate a CA with OpenSSL (`basicConstraints=critical,CA:true`, 5-year validity) in a temp dir, store PEM bundle in Key Vault, delete temp files; insert/merge `sites` row; create/update DPS enrollment group `site-<id>` (`az iot dps enrollment-group create … --auth-type login`, signing certificate, initial twin tag `siteId`).
The script only uses the deployer's Entra identity.

### D9. Observability wiring
Java services ship `applicationinsights-agent-3.x.jar` in the image (`JAVA_TOOL_OPTIONS=-javaagent:…`), `applicationinsights.json` sets role name, sampling (100% for the demo) and `authentication: { enabled: true, type: "UAMI", clientId: ${AZURE_CLIENT_ID} }`. The Application Insights connection string is injected as an environment variable: with local auth disabled it carries no usable secret. Logs: Logback JSON encoder with trace/span ids. Custom metrics via OTel `Meter`: `sdp.ingestion.messages{format,outcome,reason}`, `sdp.ingestion.batch.size`, `sdp.ingestion.latency`. The simulator sets `traceparent` per message; the decoder relies on Azure SDK instrumentation for batch-span links, adding explicit links if the SDK does not.

## Risks / Trade-offs

- [Spring Boot 4 / Java 25 compatibility of Spring Cloud Azure and the App Insights Java agent] → Verify in task 1.x with a spike; fallback: Azure Identity + plain JDBC token supplier instead of the Spring Cloud Azure starter.
- [IoT Hub identity-based endpoint requires its role assignment to exist first] → Separate endpoint/route resources with explicit `depends_on` and a `time_sleep` for RBAC propagation.
- [KEDA Event Hubs scaler with managed identity support in azurerm] → Use `azapi_update_resource` on the container app if `azurerm` lacks identity-based scale rule auth.
- [Simulator holds site CA private keys] → Acceptable for demo; documented; production uses customer PKI and pre-provisioned device certificates.
- [`pg_cron` must run in the `sdp` database] → set `cron.database_name` server parameter; requires server restart, handled by Terraform ordering.
- [Partition-key choice prevents scaling beyond 4 decoder replicas] → Sufficient for demo targets (100 msg/s).

## Migration Plan

Greenfield: no data migration. Rollback = `azd down --purge`, or redeploy previous image tags with `azd deploy`.

## Open Questions

- Whether azd natively deploys Container Apps jobs with the Terraform provider at implementation time (would simplify D5; does not change behaviour).
