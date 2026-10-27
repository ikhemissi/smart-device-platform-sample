# Tasks: M1 — Device data ingestion

## 1. Repository and build foundation

- [x] 1.1 Create the Maven parent `pom.xml` (Java 25, Spring Boot 4 BOM, Spring Cloud Azure BOM, Azure SDK BOM) and module skeletons `libs/payload-codecs`, `libs/platform-common`, `services/telemetry-decoder`, `services/device-simulator`, `services/db-migrator`; verify `mvn -q verify` succeeds on the empty modules
- [ ] 1.2 Spike: start a Spring Boot 4 / Java 25 app with the Application Insights Java agent and a passwordless PostgreSQL datasource against a dev server; verify telemetry appears in Application Insights and the DB connection uses an Entra token (record outcome in design.md Risks)
- [x] 1.3 Add a shared multi-stage Dockerfile pattern (JDK 25 distroless base, non-root user, App Insights agent, no BuildKit-only features) and verify each service image builds and starts with `podman build`/`podman run` (OCI-compatible, also used by `az acr build`)
- [x] 1.4 Add `.gitignore`, `.editorconfig`, root `README.md` (layout, prerequisites, `azd up`) and verify the README commands are accurate

## 2. Payload codecs library

- [x] 2.1 Implement the metric catalogue (names, units, descriptions) and `DecodeResult` types; verify unit tests cover catalogue completeness against the payload-decoding spec
- [x] 2.2 Implement leading-byte detection and rejection reasons (`EMPTY_PAYLOAD`, `UNKNOWN_FORMAT`, `UNSUPPORTED_FORMAT`); verify spec scenarios as tests
- [x] 2.3 Implement Ruuvi DF5 decoder/encoder incl. not-available sentinels; verify official valid/min/max vectors and round-trip property tests pass
- [x] 2.4 Implement Ruuvi DF3 decoder/encoder; verify official vectors and the negative-temperature scenario pass
- [x] 2.5 Implement BTHome v2 decoder/encoder (supported object ids, stop-on-unknown, `NO_SUPPORTED_DATA`); verify spec scenarios and round trips pass
- [x] 2.6 Implement Cayenne LPP decoder/encoder with `0xCA` marker and channel-suffix rule; verify spec scenarios (two channels, negative temperature, truncation, unknown type) pass
- [x] 2.7 Document the format registry and metric mapping in `libs/payload-codecs/README.md`; verify it matches ADR-0003 and the spec tables

## 3. Database schema and migrator

- [x] 3.1 Write Flyway `V1` (sites, devices, metric_definitions seed, partitioned measurements and rejected_messages, `ingested_messages` deduplication ledger, pg_partman parents) and the migrator's maintenance task (partitions, retention, ledger clean-up); verify migration and maintenance on a PostgreSQL 18 + pg_partman container (Testcontainers `MigrationIT`)
- [x] 3.2 Write the `afterMigrate.sql` callback creating workload principals (Entra on Azure) from placeholders, with least-privilege grants through group roles; verify with a Testcontainers test (`PrivilegesIT`) that the decoder role cannot run DDL
- [ ] 3.3 Implement the `db-migrator` runner (tasks `migrate`/`maintain`, exit code reflects success), `scripts/run-migrations.sh` and `scripts/local-postgres.sh`; verify the script migrates a dev database using the developer's Entra token (local mode verified; Entra mode pending the first `azd provision`)

## 4. Telemetry decoder

- [x] 4.1 Implement Event Hubs batch processing with Blob checkpoint store and configuration properties; verify with an integration test (Event Hubs emulator + Azurite, `DecoderPipelineIT`) that checkpoints advance only after persistence
- [x] 4.2 Implement message mapping (device id, enqueued time, siteId, message key) and batch persistence (measurements, rejected messages, aggregated device upsert) in one transaction; verify idempotency (incl. the 'device resends a message later' scenario) and auto-registration scenarios with Testcontainers
- [x] 4.3 Implement quarantine rules incl. `MISSING_SITE`; verify the "corrupted message in a batch" scenario
- [x] 4.4 Implement retry with back-off on database failures and readiness reporting; verify the "storage unavailable" scenario by making the database unavailable to the decoder mid-test (`DecoderPipelineIT`)
- [x] 4.5 Add custom metrics, span attributes and JSON logs with trace ids; verify via an OTel in-memory exporter test
- [x] 4.6 Document decoder configuration and operations in `services/telemetry-decoder/README.md`; verify documented properties match `application.yaml`

## 5. Device simulator

- [x] 5.1 Implement fleet configuration, deterministic device ids and reading models; verify unit tests for defaults, custom fleet and value continuity
- [x] 5.2 Implement site-CA loading from Key Vault and in-memory leaf certificate generation; verify a generated leaf chains to the CA and has CN = device id
- [ ] 5.3 Implement DPS provisioning and IoT Hub MQTT sending with message id and `traceparent`; verify against the dev environment that a device provisions and messages arrive in Event Hubs (message mapping unit-tested; dev-environment verification pending the first `azd provision`)
- [x] 5.4 Implement corruption and anomaly injection and graceful shutdown; verify unit tests for injection rates/anomaly windows and a shutdown test within 10 seconds
- [x] 5.5 Document local and cloud usage (incl. on-demand anomalies) in `services/device-simulator/README.md`; verify commands run as written (dry-run command verified; Azure commands are exercised by 5.3)

## 6. Infrastructure (Terraform)

- [ ] 6.1 Create `scripts/bootstrap-tfstate.sh`, `infra/provider.tf`, `infra/provider.conf.json`, variables (regions, env name, demo sites) and naming/tagging conventions; verify `terraform init` with the Entra-authenticated backend
- [ ] 6.2 Implement module `core` (RG, Log Analytics, App Insights with local auth disabled, ACR, ACA environment, Key Vault RBAC); verify `terraform plan` and `azd provision` succeed
- [ ] 6.3 Implement module `identity` (per-workload UAMIs and role assignments); verify roles with `az role assignment list` match the RBAC matrix in `docs/architecture/implementation-guidelines.md`
- [ ] 6.4 Implement module `ingestion` (Event Hubs, Storage, IoT Hub with identity-based endpoint, route and siteId enrichment, DPS link); verify a test message sent with `az iot device simulate` reaches the event hub with `siteId`
- [ ] 6.5 Implement module `data` (PostgreSQL Entra-only, extensions/parameters, database, migrator as Entra admin); verify password login is refused and the migrator identity can connect
- [ ] 6.6 Define container apps (decoder with identity-based KEDA scaler, simulator), the migrator job (manual, `migrate,maintain`) and the hourly maintenance job (`maintain`); verify `azd deploy` updates images, apps become healthy and a scheduled maintenance execution succeeds
- [ ] 6.7 Verify no secret-bearing outputs/values exist (`azd env get-values`, `terraform output`) and document the DPS link exception in `docs/security.md`

## 7. azd wiring and demo bootstrap

- [ ] 7.1 Write `azure.yaml` (services, hooks) and the `postprovision` hook running migrations then `scripts/bootstrap-demo-sites.sh`; verify `azd up` on an empty environment completes without manual steps
- [ ] 7.2 Implement `scripts/bootstrap-demo-sites.sh` (idempotent CA generation into Key Vault, `sites` rows, DPS enrollment groups with initial twin tag); verify a second run keeps existing CAs
- [ ] 7.3 Verify `azd down --purge` removes all resources including soft-deleted Key Vault

## 8. End-to-end verification

- [ ] 8.1 Run the default simulator fleet for 15 minutes; verify measurements for all 30 devices and 4 formats are in PostgreSQL and devices are auto-registered with correct sites
- [ ] 8.2 Run 100 msg/s for 10 minutes with 1% corruption; verify p99 ingestion latency < 60 s, rejected messages stored with reasons and no duplicates
- [ ] 8.3 Follow one simulated message end to end in Application Insights (send span → decoder batch span → DB write); verify span attributes `sdp.device.id`, `sdp.site.id`, `sdp.payload.format`
- [ ] 8.4 Attempt provisioning with a certificate from an unknown CA; verify it is refused
- [ ] 8.5 Write the M1 demo guide `docs/demo/m1-ingestion.md` following PRD-001 §6; verify a dry run follows it as written

## Workflow follow-up

- Archive the change after review so its specs become the baseline in `openspec/specs/`.
