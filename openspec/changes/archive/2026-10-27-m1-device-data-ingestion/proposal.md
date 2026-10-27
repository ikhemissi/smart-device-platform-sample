# Proposal: M1 — Device data ingestion

## Why

Milestone M1 ([PRD-001](../../../docs/prd/PRD-001-m1-device-data-ingestion.md)) must prove that measurements from heterogeneous, real-world binary device formats reach the platform, are decoded without out-of-band hints and are stored with history. Every later milestone (portal, charts, alerting, AI agent) consumes this data, so the ingestion path and the deployable platform foundation come first.

## What Changes

- Decode four real device formats — Ruuvi DF5, Ruuvi DF3, BTHome v2 (unencrypted), Cayenne LPP (with format marker) — detected from the payload's leading byte, into canonical metrics with fixed units.
- Ingest device messages end to end: devices → IoT Hub (X.509 via DPS per-site enrollment groups) → Event Hubs → batch decoder → PostgreSQL, with idempotent writes, device auto-registration and rejected-message quarantine.
- Provide a configurable device simulator that provisions devices through DPS and emits realistic readings in all four formats across several sites.
- Provide a one-command, passwordless, observable deployment of the M1 platform foundation with azd + Terraform in `francecentral`, including demo site CAs and enrollment groups.

## Capabilities

### New Capabilities

- `payload-decoding`: Leading-byte format detection, per-format decoding rules, canonical metric names/units and rejection reasons.
- `telemetry-ingestion`: Receipt, batch decoding, idempotent persistence, device auto-registration, quarantine of undecodable messages and history retention.
- `device-simulation`: Simulated multi-site device fleet: provisioning, payload generation, configuration and fault injection.
- `platform-deployment`: One-command provisioning/deployment/teardown of an environment, regions, demo bootstrap (sites, CAs, enrollment groups, schema).
- `platform-security`: Passwordless workload access, disabled local auth, least privilege, device certificate authentication, documented exceptions.
- `platform-observability`: OpenTelemetry traces/metrics/logs to Application Insights, end-to-end correlation and domain metrics.

### Modified Capabilities

_None — first change of the project._

## Impact

- **New code**: Maven multi-module build (`libs/payload-codecs`, `libs/platform-common`), services `telemetry-decoder`, `device-simulator`, `db-migrator`; `infra/` Terraform root + modules (`core`, `identity`, `ingestion`, `data`); `azure.yaml`; azd hooks; `scripts/` for demo PKI.
- **Azure resources**: resource group, Log Analytics + Application Insights, ACR, ACA environment, user-assigned identities, IoT Hub (S1), DPS, Event Hubs namespace (Standard), Storage (checkpoints), PostgreSQL Flexible Server (Entra-only), Key Vault.
- **ADRs relied on**: ADR-0001 to ADR-0006, ADR-0008; implementation guidelines.
- **Downstream contracts introduced**: canonical metric names/units, `measurements`/`devices`/`sites` schema, device ID convention.
