# Proposal: M4 — Site certificate management

## Why

Milestone M4 ([PRD-004](../../../docs/prd/PRD-004-m4-site-certificates.md)) lets Editors control which devices may join the platform, site by site, by managing the certificate authorities trusted for each site — including zero-downtime renewal with two certificates. Until now, demo sites and their trust were created only by deployment tooling.

## What Changes

- A provisioning API service, behind the same gateway, to create sites and add/remove site certificates, backed by one DPS enrollment group per site (ADR-0006).
- Validation of uploaded certificates (CA, validity, no private key, max two per site, no duplicates) and an audit trail of changes.
- Disabling a site's devices in IoT Hub when its last certificate is removed, and re-enabling them when a certificate is added again.
- Reconciliation so that sites created by the deployment bootstrap appear and are manageable.
- Portal pages for sites and their certificates, with Editor-only actions and expiry warnings.

## Capabilities

### New Capabilities

- `site-certificates`: Site creation, site certificate listing/adding/removing with validation, effects on device provisioning and connectivity, audit, reconciliation and the corresponding portal experience.

### Modified Capabilities

_None (the M2 `GET /api/v1/sites` behaviour is unchanged)._

## Impact

- **New code**: `services/provisioning-api`; Flyway migration (`site_certificates`, `site_audit_events`, `devices.disabled_by_platform`); portal "Sites" section; APIM operation-level routing.
- **Azure resources**: provisioning-api container app and identity with DPS data-plane and IoT Hub registry roles.
- **ADRs relied on**: ADR-0005, ADR-0006, ADR-0007.
