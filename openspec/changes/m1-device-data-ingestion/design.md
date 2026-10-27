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
libs/db-schema/                # Flyway migrations (classpath:db/migration), reused by service integration tests
libs/test-support/             # Testcontainers PostgreSQL 18 + pg_partman image, Podman adaptation (test scope)
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
- Inputs from IoT Hub/Event Hubs (read from the AMQP message annotations, which is also how tests build IoT Hub-like events): device id from system property `iothub-connection-device-id`, measurement time from `iothub-enqueuedtime`, site from application property `siteId` (IoT Hub enrichment), message key = `message-id` system property or `<partition>:<sequenceNumber>`.
- Errors: DB exceptions → no checkpoint, in-process exponential back-off (1 s doubling to 30 s, no extra library) until persisted; on shutdown the unpersisted batch is abandoned and redelivered from the last checkpoint; decode errors, missing device (`MISSING_DEVICE`) or missing/invalid site (`MISSING_SITE`, validated with the `sites` check rule so it can never poison a batch) → rejected rows. Unknown (valid) sites are created rather than failing the batch on the foreign key.
- Health: Spring Boot actuator liveness/readiness on port 8080 (probes only, no ingress); readiness includes an `ingestion` indicator that is `DOWN` while batches cannot be persisted.
*Alternative*: Spring Cloud Stream binder for Event Hubs — rejected; less control over batch-level transaction/checkpoint ordering.

### D4. Database schema (Flyway `V1__…`, PostgreSQL 18)
- Schema `sdp` owned by the migrator; pg_partman 5 in schema `partman`.
- `sites(site_id text pk, display_name, created_at)`; `devices(device_id text pk, site_id fk, display_name, description, location jsonb, last_payload_format, first_seen_at, last_seen_at, updated_at, updated_by)`. The decoder inserts an unknown site (display name = id) rather than failing a batch on the foreign key.
- `metric_definitions(name pk, unit, description)` seeded by the repeatable Java migration `R__Metric_definitions` from the codec catalogue (checksum derived from the catalogue, so it re-runs when metrics change).
- `measurements(device_id, site_id, measured_at timestamptz, received_at, message_key, payload_format, raw_payload bytea, metrics jsonb, attributes jsonb, PRIMARY KEY (device_id, measured_at, message_key)) PARTITION BY RANGE (measured_at)`; `rejected_messages(received_at, enqueued_at, device_id, site_id, message_key, reason, detail, raw_payload) PARTITION BY RANGE (received_at)` without primary key (deduplicated through the ledger), indexed on `received_at` and `(device_id, received_at)`.
- `ingested_messages(device_id, message_key, ingested_at, PRIMARY KEY (device_id, message_key))` — non-partitioned deduplication ledger, purged after 7 days (Event Hubs retention is 1 day, device resends happen within minutes).
- `partman.create_parent(... '1 day', premake 7)`, `infinite_time_partitions`, `retention = 30 days`, `retention_keep_table = false`. Server parameter `azure.extensions = PG_PARTMAN` (Terraform); no `shared_preload_libraries` change is needed because the background worker is not used.
- Roles: migrator MI is the server's Entra administrator and schema owner. Privileges are granted to NOLOGIN **group roles** in versioned migrations (`sdp_ingest` for the decoder: `INSERT` measurements/rejected_messages/ingested_messages + `SELECT(device_id, message_key)` on the ledger for `RETURNING`, `SELECT, INSERT` sites/devices, `UPDATE(last_seen_at, last_payload_format)` devices). Before each migration the migrator creates each workload principal idempotently through a separate connection to the `postgres` database, the only one where Azure's `pgaadauth` functions exist (`pgaadauth_create_principal_with_oid`, looked up by name as its signature evolves; a same-named plain role is replaced); after the migration it grants the group role. On plain PostgreSQL principals are `LOGIN` roles. Application roles never own objects.
- *Alternative*: `pg_cron` for maintenance — rejected: pg_cron jobs run as the scheduling role and need a local connection for it, which conflicts with Entra-only principals; maintenance in the migrator is identity-based, observable and testable locally.

### D5. Migration and maintenance execution
`db-migrator` is a Spring Boot + Flyway non-web app (tasks `migrate` and/or `maintain`, exit code = outcome) packaged as an image and run as **Container Apps jobs** under the migrator identity:
- `migrate,maintain` (manual trigger): the azd `postprovision` hook builds/pushes its image with `az acr build` (Entra auth, no local Docker needed), updates the job image, starts it and waits for success — so the schema exists before `azd deploy` starts the services;
- `maintain` (schedule `0 * * * *`): pre-creates partitions, drops expired ones, purges the ledger.
Locally, `scripts/run-migrations.sh` runs the same app with the developer's Entra token (developer added as additional Entra admin in dev only), or `--local` against `scripts/local-postgres.sh`.
*Alternative*: Flyway at decoder start-up — rejected (decoder would need DDL rights, violating least privilege).

### D6. Device simulator
- Spring Boot non-web app; one virtual thread per device (main thread waits for the simulation to end, so the JVM stays alive); Azure IoT device SDK 2.x (MQTT) with a `SecurityProviderX509` + provisioning device client. A `DeviceTransport` abstraction has two implementations: `iothub` (DPS + IoT Hub) and `console` (dry run, no Azure).
- Site CA PEM bundle (cert + PKCS#8 or OpenSSL traditional key) read from Key Vault secret `site-<siteId>-ca` with the simulator MI (Key Vault Secrets SDK + `DefaultAzureCredential`), or from a local directory for offline runs; device leaf certificates (EC P-256, CN = device id, client-auth EKU, 1-year validity) generated in memory with BouncyCastle at start-up. The site CA is presented as the device's chain (it is the enrollment group's signing certificate).
- Reading model per device: seeded RNG (seed = hash(device id)) → stable per-device baselines; temperature = base + amplitude·sin(2π·t/24h) + noise; humidity anti-correlated; battery decay; counters increase; values clamped to each format's range. Message id = random UUID per message (kept by the SDK on retries), `traceparent` from a `sdp.device.send` PRODUCER span.
- Configuration via Spring properties/env: `SDP_SIM_SITES`, `SDP_SIM_DEVICES_PER_SITE`, `SDP_SIM_FORMAT_MIX`, `SDP_SIM_INTERVAL`, `SDP_SIM_JITTER`, `SDP_SIM_DURATION`, `SDP_SIM_CORRUPTION_RATE`, `SDP_SIM_ANOMALIES` (e.g. `paris-01-ruuvi5-001:temperature=12:5m[:<delay>]`), `SDP_SIM_TRANSPORT`, `SDP_DPS_ID_SCOPE`, `SDP_KEYVAULT_URI`. On-demand anomalies = `az containerapp update --set-env-vars` (new revision) — documented in the simulator README. Deployed with `minReplicas = maxReplicas = 1`, no ingress.
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
2. `scripts/bootstrap-demo-sites.sh`: for each site in `DEMO_SITES` — if Key Vault secret `site-<id>-ca` is absent, generate a CA with OpenSSL (EC P-256, `basicConstraints=critical,CA:true,pathlen:0`, 5-year validity) in a temp dir, store the PEM bundle in Key Vault, delete temp files; register the CA in DPS as a verified CA certificate `site-<id>`; create the DPS enrollment group `site-<id>` referencing it (`--ca-name`, initial twin tag `siteId`, `--auth-type login`). `sites` rows are created by the decoder from the first message of each site.
The script only uses the deployer's Entra identity.

### D9. Observability wiring
Java services ship `applicationinsights-agent-3.x.jar` in the image (`JAVA_TOOL_OPTIONS=-javaagent:…`), `applicationinsights.json` sets role name, sampling (100% for the demo) and `authentication: { enabled: true, type: "UAMI", clientId: ${AZURE_CLIENT_ID} }`. The Application Insights connection string is injected as an environment variable: with local auth disabled it carries no usable secret. Logs: Logback JSON encoder with trace/span ids. Custom metrics via OTel `Meter`: `sdp.ingestion.messages{format,outcome,reason}`, `sdp.ingestion.batch.size`, `sdp.ingestion.latency`. The simulator sets `traceparent` per message; the decoder creates a `sdp.ingest.batch` span per batch with one child `sdp.ingest.message` span per message, explicitly linked to the message's `traceparent` and carrying `sdp.device.id`, `sdp.site.id`, `sdp.payload.format` and the outcome. In Application Insights the navigable link is on the Event Hubs SDK's `process device-telemetry` request (one per batch, `_MS.links` to every message's send span, read from the `traceparent` property); the Azure Monitor exporter does not export links of internal spans. `scripts/trace-device.sh` assembles send → process → message → database writes → checkpoint. The OpenTelemetry API is obtained from a `platform-common` auto-configuration (agent-provided `GlobalOpenTelemetry`; SDK with in-memory exporters in tests).

## Findings from the first deployment

- Tenant policies (*MCAPSGov*) force public network access off on Storage/Key Vault and block `westeurope`: resources carry `SecurityControl=Ignore` and `CostControl=Ignore` (guidelines §7); Static Web Apps moves to `eastus2` (ADR-0008).
- DPS rejects device chains that do not end at a verified CA (`401002`): site CAs are registered as verified CA certificates and referenced by enrollment groups (ADR-0006).
- Entra principals must be created from the `postgres` database (see D4).
- `az acr build` only honours `.dockerignore` rules without trailing slashes, and the ACR classic builder can fail on consecutive `COPY --from` steps: the shared Dockerfile copies the merged Spring Boot layers once.
- A fresh `azd up` hit `ServerIsBusy` on PostgreSQL (parallel child operations): they are now chained. `terraform destroy` hung deleting a PostgreSQL Entra administrator: a `predown` hook deletes the resource group in one operation. The state backend is configured by a `preprovision` hook and health probes by a `postdeploy` hook, so a new environment needs a single `azd up`.
- The IoT provisioning SDK leaves a non-daemon executor thread after a refused registration: the simulator exits the JVM explicitly when the simulation ends.
- Load: 300 devices offering 112 msg/s for 10 minutes on IoT Hub S1 (1 unit) and one decoder replica — no throttling, p99 5.1 s (bounded by the 5 s batch wait), 0 duplicates, 0.96% corrupted messages quarantined.

## Risks / Trade-offs

- [Spring Boot 4 / Java 25 compatibility of Spring Cloud Azure and the App Insights Java agent] → Verify in task 1.x with a spike; fallback: Azure Identity + plain JDBC token supplier instead of the Spring Cloud Azure starter.
  - *Spike result (2026-10-26/27)*: confirmed in Azure — the migrator job and the decoder authenticate to PostgreSQL with Entra tokens of their managed identities, and the agent exports traces, metrics and logs to Application Insights with Entra-only ingestion. Local details: Spring Boot 4.1.2 + Spring Cloud Azure 7.4.0 (`spring-cloud-azure-starter-jdbc-postgresql`, passwordless enabled) start correctly on Microsoft OpenJDK 25.0.5 with virtual threads; Application Insights agent 3.7.10 attaches on Java 25 and attempts managed-identity (UAMI) authentication. The agent needs `--enable-native-access=ALL-UNNAMED` (Netty native access warning) and adds a few seconds of JVM start-up. Pending: token-authenticated PostgreSQL connection and ingestion against real Azure resources (completed with the first `azd provision`, tasks 6.2/6.5).
- [IoT Hub identity-based endpoint requires its role assignment to exist first] → Separate endpoint/route resources with explicit `depends_on` and a `time_sleep` for RBAC propagation.
- [KEDA Event Hubs scaler with managed identity support in azurerm] → Use `azapi_update_resource` on the container app if `azurerm` lacks identity-based scale rule auth.
- [Simulator holds site CA private keys] → Acceptable for demo; documented; production uses customer PKI and pre-provisioned device certificates.
- [A missed hourly maintenance run] → 7 future daily partitions are pre-created and a default partition catches out-of-range rows, so ingestion never fails; the next run catches up.
- [Partition-key choice prevents scaling beyond 4 decoder replicas] → Sufficient for demo targets (100 msg/s).

## Migration Plan

Greenfield: no data migration. Rollback = `azd down --purge`, or redeploy previous image tags with `azd deploy`.

## Open Questions

- Whether azd natively deploys Container Apps jobs with the Terraform provider at implementation time (would simplify D5; does not change behaviour).
