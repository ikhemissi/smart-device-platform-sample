# Proposal: M2 — Device catalog in the web portal

## Why

Milestone M2 ([PRD-002](../../../docs/prd/PRD-002-m2-device-list.md)) makes the ingested fleet visible to people: users sign in with Entra ID and browse the devices the platform knows about, and Editors keep device information accurate. It also establishes the user-facing foundation — identity, roles, API gateway and web portal — that M3 to M6 build on.

## What Changes

- Sign-in with Microsoft Entra ID and two app roles (`DeviceData.Read`, `DeviceData.Write`) enforced at the API gateway and in every backend.
- A device catalog API (list with filter/search/sort/paging, details, metadata update with optimistic concurrency, sites list, current-user endpoint) exposed through Azure API Management.
- A web portal (SPA on Azure Static Web Apps) with sign-in, an access-denied page, the device list and an Editor-only edit dialog.
- Infrastructure: Entra app registrations, APIM, Static Web App, device API container app, database grants.

## Capabilities

### New Capabilities

- `user-access`: Authentication of users, the two permission levels, enforcement on every API, current-user information and audit identity.
- `device-catalog`: API behaviour for listing, searching, reading and editing devices and listing sites, including reporting/silent status.
- `web-portal`: The browser application shell (sign-in, navigation, access denied, error handling) and the device list / edit experience.

### Modified Capabilities

_None._

## Impact

- **New code**: `services/device-api`, `web/portal`, `infra/modules/{api,web}` and Entra app registrations in `infra/modules/identity`; Flyway migration adding device-api grants.
- **Azure resources**: APIM (BasicV2), Static Web App (Free, `eastus2`), device-api container app and identity, two Entra app registrations with role assignments for the deployer.
- **ADRs relied on**: ADR-0005, ADR-0007, ADR-0008; implementation guidelines (API conventions).
- **Contracts introduced**: public API base path `/api/v1`, error format (RFC 9457), role names, device JSON representation.
