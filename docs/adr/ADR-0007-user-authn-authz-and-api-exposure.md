# ADR-0007 — User authorization model and API exposure

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

Users sign in with Microsoft Entra ID; two permissions exist (query and edit device data). The UI is an SPA on Azure Static Web Apps calling APIs through Azure API Management (APIM). The AI agent must only access what the calling user may access.

## Decision

1. **One Entra application represents the platform API** and is the **single token audience for every backend API** (device API, provisioning API, agent API).
2. **Authorization uses two app roles** on that application, assigned to users/groups: **`DeviceData.Read`** and **`DeviceData.Write`** (Write implies Read). Each operation declares its required role by semantics: changing platform data requires Write; reading (and using one's own assistant conversations) requires Read.
3. **The SPA authenticates users itself with MSAL** (authorization code + PKCE) against a separate SPA app registration; Static Web Apps built-in authentication is not used.
4. **APIM is the single public entry point for APIs** and enforces token validation and roles; backends trust only tokens for the platform API audience.
5. **The AI agent calls platform APIs with the user's own access token** (same audience), so every data access is evaluated against the user's roles.

## Why hard to reverse

Role names appear in tokens, role assignments, gateway policies and every backend; the audience is configured in every client and service. The gateway is the published endpoint consumed by the SPA and agent. The agent's "act as the user" model is a security property that other designs (tools, auditing) rely on.

## Consequences

- One consent and one token for the SPA; token forwarding to the agent's downstream calls is valid without On-Behalf-Of.
- Authorization is coarse-grained (a role applies to all sites/devices); per-site authorization would need a new ADR.
- Requires permission to create app registrations in the tenant (confirmed).

## Alternatives considered

| Option | Why not |
|---|---|
| Static Web Apps built-in auth + linked backend | Less control over scopes/tokens; linked APIM requires the Standard plan. |
| One app registration per API + OBO for the agent | Several tokens for the SPA and a confidential client for the agent; no benefit for the demonstrator. |
| Authorization only in APIM | Backends on Container Apps are publicly reachable ([ADR-0008](ADR-0008-infrastructure-as-code.md)). |
| Scopes instead of app roles | Scopes express client permissions, not user permissions. |
