# Design: M4 — Site certificate management

## Context

M1 bootstrap creates demo site CAs (Key Vault) and DPS enrollment groups `site-<siteId>` with initial twin tag `siteId`. ADR-0006 defines one enrollment group per site with primary/secondary signing certificates. See [proposal.md](proposal.md) and `specs/`.

## Goals / Non-Goals

**Goals:** a small, separately deployable service owning DPS/IoT Hub control-plane interactions; DPS stays the source of truth for trust.

**Non-Goals:** issuing certificates, per-device revocation, disconnecting live sessions immediately (disabled devices are refused on their next connection/token refresh).

## Decisions

### D1. Provisioning API service
`services/provisioning-api` (Spring Boot 4) with its own managed identity holding **Device Provisioning Service Data Contributor** on DPS and **IoT Hub Registry Contributor** on IoT Hub. Uses the Azure IoT `provisioning-service-client` and `iot-service-client` with `TokenCredential` (no SAS). Shares security/problem-details auto-configuration from `libs/platform-common`.

### D2. Mapping to DPS
- Site ⇔ enrollment group `site-<siteId>`, X.509 attestation with signing certificates in `primary`/`secondary`, `initialTwin.tags.siteId`, reprovision policy "update hub assignment + migrate data".
- Add: read group (ETag) → place cert in free slot (create group if absent; re-enable if disabled) → `createOrUpdate` with ETag → on success commit DB rows. Remove: clear slot (if removing primary while secondary exists, secondary is moved to primary) → if none left, set `provisioningStatus = disabled`.
- Consistency: DPS is authoritative. DPS call first with optimistic ETag (failure → 503, nothing changed); DB transaction second. If the DB write fails after DPS succeeded, the API returns 500 and the hourly/startup reconciliation (D5) repairs the records and writes a `RECONCILED_*` audit entry — no compensating DPS call (which could itself fail).
- Device disable/re-enable (D3) is idempotent and tracked in an `site_operations` table (`PENDING/DONE/FAILED`); reconciliation retries pending operations.

### D3. Device disable/re-enable
On last-certificate removal: query devices of the site from PostgreSQL, set `status = disabled` in IoT Hub registry (bulk import job for > 100 devices), and set `devices.disabled_by_platform = true`. On first certificate added to such a site: re-enable only devices with `disabled_by_platform = true`, then clear the flag. Long operations run asynchronously; the API returns 202 with an operation id when > 100 devices (demo fleets are smaller, so the default path is synchronous).

### D4. Certificate validation
Java `CertificateFactory` (PEM/DER, max 64 KB). Reject: private key blocks (detect `PRIVATE KEY` markers before parsing; payload discarded, never logged), `basicConstraints < 0`, `notAfter < now` or `notBefore > now`, duplicate SHA-256 fingerprint within the site. Store metadata only (subject, issuer, fingerprint, validity, slot, added_by, added_at) — the PEM itself lives in DPS.

### D5. Reconciliation
On start-up and hourly (`@Scheduled`, single instance via `pg_try_advisory_lock`): list enrollment groups `site-*`, upsert `sites` and `site_certificates` with `added_by = 'deployment'` when unknown; mark DB certificates absent from DPS as removed (audit `RECONCILED_REMOVED`).

### D6. API routing in APIM
Single APIM API `sdp-api` (`/api/v1`). Operations from device-api and provisioning-api OpenAPI documents are merged at build time (`scripts/merge-openapi.mjs`); provisioning operations carry an operation-level `set-backend-service backend-id="provisioning-api"` policy. `POST /api/v1/sites` and `/sites/{id}/certificates*`, `/sites/{id}/audit` → provisioning-api; all other operations → device-api.

### D7. Portal
New "Sites" navigation entry; site list (device count from device API, certificate summary from provisioning API), site page with certificates table, upload dialog (file input, client-side size/type check, server is authoritative), destructive-action confirmation that changes wording when removing the last certificate.

## Risks / Trade-offs

- [DPS and IoT Hub data-plane RBAC role names/availability in Terraform] → verify built-in role names at implementation; fallback to scoped custom roles.
- [Already-registered devices keep connecting after a CA removal while another CA remains] → by specification (PRD-004 AC5): removal blocks join/re-join; only last-certificate removal disables devices. Documented in the demo guide.
- [Removing a certificate from the M1 bootstrap CA while the simulator runs] → simulator logs provisioning failures and retries with back-off; useful in the demo.

## Migration Plan

Additive migration: `site_certificates`, `site_audit_events`, `site_operations`, `devices.disabled_by_platform default false`. First start of provisioning-api reconciles bootstrap sites.
