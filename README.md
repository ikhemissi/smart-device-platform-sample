# Smart Device Platform — demonstrator

End-to-end Azure demonstrator of a sensor platform: devices of several real-world binary formats send measurements through IoT Hub and Event Hubs, a batch decoder stores them in PostgreSQL, users browse devices and charts in a web portal, editors manage site certificates and alert thresholds, email alerts are raised for monitored devices, and an AI agent answers questions about the data.

> **Status:** M1–M4 delivered and archived (baseline specs in `openspec/specs/`); M5 (threshold alerting) implemented and verified in the `sdp-dev` environment, to be archived after review; M6 (AI analyst) next — see `openspec list`.

## Documentation

| What | Where |
|---|---|
| Product requirements (business only) and milestones | [docs/prd](docs/prd/PRD-000-product-overview.md) |
| Architecture decisions (hard to reverse only) | [docs/adr](docs/adr/README.md) |
| Implementation guidelines (cross-cutting, reversible conventions) | [docs/architecture](docs/architecture/implementation-guidelines.md) |
| Specifications, designs and task lists (spec-driven, one change per milestone) | [openspec/changes](openspec/changes) |

## Milestones

| # | Milestone | OpenSpec change | Status | Demo guide |
|---|---|---|---|---|
| M1 | Device data is stored | `m1-device-data-ingestion` | Archived | [m1-ingestion](docs/demo/m1-ingestion.md) |
| M2 | Device list in the web portal | `m2-device-catalog-portal` | Archived | [m2-device-list](docs/demo/m2-device-list.md) |
| M3 | Device data and chart | `m3-telemetry-history-and-chart` | Archived | [m3-device-data-and-chart](docs/demo/m3-device-data-and-chart.md) |
| M4 | Site certificate management | `m4-site-certificate-management` | Archived | [m4-site-certificates](docs/demo/m4-site-certificates.md) |
| M5 | Threshold alerting | `m5-threshold-alerting` | Done, archive pending review | [m5-threshold-alerting](docs/demo/m5-threshold-alerting.md) |
| M6 | AI analyst | `m6-ai-device-analyst` | Next | — |

## Architecture at a glance

```
Simulated devices (X.509 per site) ──► DPS ──► IoT Hub ──route──► Event Hubs ──► telemetry-decoder (ACA) ──► PostgreSQL
                                                                                        │
                                                                                        └──► Service Bus topic ──filtered sub──► alert-service (ACA) ──► ACS Email
SPA (Static Web Apps) ──► API Management ──► device-api (ACA) ──► Redis cache / PostgreSQL
                                        ├──► provisioning-api (ACA) ──► DPS / IoT Hub
                                        └──► device-analyst agent (ACA, Python) ──► Foundry model
                                                                                └──► platform API (user token)
All workloads ──OpenTelemetry──► Application Insights      Identity: Entra ID + managed identities (passwordless)
```

Stack: Java 25 + Spring Boot 4 (platform services), Python + Microsoft Agent Framework (agent), React + TypeScript (portal), Terraform deployed with azd. Region `francecentral` (Static Web Apps: `eastus2`, as `westeurope` is blocked by tenant policy).

## Repository layout

```
libs/payload-codecs/        Format detection, decoders and encoders (pure Java)
libs/device-fleet/          Simulated fleet and reading model (simulator, demo history backfill)
libs/platform-common/       Shared Spring Boot auto-configuration (security, observability)
libs/db-schema/             Flyway migrations (PostgreSQL schema)
libs/test-support/          Testcontainers definitions for integration tests (PostgreSQL, Event Hubs and Service Bus emulators)
services/telemetry-decoder/ Event Hubs → PostgreSQL batch decoder, publishes measurements to Service Bus (see its README)
services/device-simulator/  Simulated multi-site device fleet (see its README)
services/db-migrator/       Applies migrations and DB maintenance (Container Apps jobs)
services/device-api/        Devices, telemetry history, thresholds and alerts API behind API Management (see its README)
services/provisioning-api/  Site trust: CA certificates per site in DPS, device enable/disable (see its README)
services/alert-service/     Threshold evaluation and alert emails (Service Bus → ACS Email, see its README)
web/portal/                 Web portal: React, Fluent UI, MSAL (see its README)
services/Dockerfile         Shared image definition for Java services
scripts/                    Developer and deployment scripts
infra/                      Terraform (deployed with azd)
docs/  openspec/            Requirements, decisions, specs
```

## Prerequisites

- **JDK 25** (e.g. [Microsoft Build of OpenJDK](https://learn.microsoft.com/java/openjdk/download)) with `JAVA_HOME` set — the build refuses older JDKs. Maven is not needed: use the wrapper `./mvnw`.
- **Podman** (or Docker) to build images and run Testcontainers-based tests. With Podman, expose its socket to Testcontainers:
  ```bash
  systemctl --user enable --now podman.socket
  export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
  export TESTCONTAINERS_RYUK_DISABLED=true
  ```
- **Node.js 22+** for the web portal.
- **Azure CLI**, **Azure Developer CLI (azd)**, **Terraform** for deployment.
- **OpenSpec CLI** (`npm i -g @fission-ai/openspec`) to work with specs.

## Build and run locally

```bash
./mvnw test                                              # unit tests (no container engine needed)
./mvnw verify                                            # + integration tests (*IT, Testcontainers)
scripts/local-postgres.sh start && scripts/run-migrations.sh --local   # local database with the schema
podman build -f services/Dockerfile \
  --build-arg SERVICE=telemetry-decoder -t sdp/telemetry-decoder:dev .   # build one service image
cd web/portal && npm ci && npm test && npm run build     # portal (see web/portal/README.md)
cd web/portal && npm run e2e:podman                      # portal end-to-end tests in Chromium, API faked
```

## Deploy to Azure

Prerequisites: Owner (or Contributor + User Access Administrator) on the subscription, `az` with the `azure-iot` extension (`az extension add -n azure-iot`), `azd`, Terraform, OpenSSL. No Docker needed: images are built in Azure Container Registry.

```bash
az login && azd auth login
azd env new sdp-dev --location francecentral --subscription <subscription-id>
azd up                                  # state backend, infrastructure, database migration, demo sites, services
```

`azd up` first makes sure the Terraform state store exists (`scripts/bootstrap-tfstate.sh`, Entra-only storage shared by all environments of the subscription), provisions everything with Terraform (`infra/`), then the post-provision hook builds the migrator image, migrates the database and prepares the demo sites (CA per site in Key Vault, verified in DPS, enrollment groups). Finally azd deploys the services (telemetry-decoder, device-simulator, device-api, provisioning-api, alert-service) and the portal (`web`). The simulator starts sending immediately (3 sites × 10 devices every 30 s). Re-running `azd up` is idempotent; `azd deploy <service>` redeploys one service. Remove everything with `azd down --purge` (deletes the resource group and purges the Key Vault).

Environment settings (`azd env set <name> <value>`, then `azd provision`):

| Setting | Default | Effect |
|---|---|---|
| `ALERTING_SUBSCRIPTION_FILTER` | `siteId = 'paris-01'` (when empty) | Service Bus SQL filter selecting the devices monitored for alerts, e.g. `siteId IN ('paris-01','lyon-01')` |

Guided demos are listed in [Milestones](#milestones). Helper scripts: `scripts/psql.sh` (database with your Entra identity), `scripts/trace-device.sh` (follow a device's messages in Application Insights), `scripts/backfill-history.sh` (one-off 30-day demo history), `scripts/remove-test-site.sh` (remove a `test-*`/`e2e-*` site and its devices, DPS objects and records).

All resources carry the tenant governance tags `SecurityControl=Ignore` and `CostControl=Ignore` (see [docs/security.md](docs/security.md)).

## Working with specs

```bash
openspec list                      # changes and task progress
openspec show m5-threshold-alerting
openspec validate --all --strict
```

In GitHub Copilot, use `/opsx-apply` to implement a change and `/opsx-archive` once it is done.
