# ADR-0005 — Passwordless identity model

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

The platform must be fully passwordless: no passwords, keys, SAS tokens or secret-bearing connection strings in code, configuration, pipelines or IaC outputs.

## Decision

1. **Every workload authenticates to Azure with its own user-assigned managed identity** through Microsoft Entra ID, with least-privilege RBAC on exactly the resources it uses.
2. **Key/password-based authentication is disabled** on every data service that supports it (PostgreSQL, Redis, Event Hubs, Service Bus, Storage, Key Vault, Application Insights ingestion, Foundry/AI Services, Communication Services usage), and data-plane access is granted through Entra ID only.
3. **Devices authenticate with X.509 certificates** ([ADR-0006](ADR-0006-device-identity-per-site-ca.md)); no symmetric keys.
4. **Documented exceptions**, imposed by the platform and never handled by people or used by workloads:
   - the **DPS → IoT Hub link** requires an IoT Hub shared access policy (DPS does not support identity-based linking), so IoT Hub local auth cannot be fully disabled;
   - the **Static Web Apps deployment token** is fetched at deploy time by azd with the deployer's Entra identity and never stored;
   - **Terraform state** may hold the DPS link value; the state backend is Entra-protected.

## Why hard to reverse

Identity-based access shapes SDK usage in every service, the RBAC wiring of every resource and the database principal model. Re-introducing secrets later would require a secret store, rotation and changes everywhere; moving from secrets to identities later is a platform-wide retrofit.

## Consequences

- No secret rotation, no secret leakage risk for service credentials.
- Every new resource needs explicit RBAC wiring; role propagation delays must be tolerated during deployment.
- Database principals for managed identities are created by an Entra-authenticated administrator identity.
- Developers need Entra roles on dev resources to run services locally.

## Alternatives considered

| Option | Why not |
|---|---|
| Secrets in Key Vault referenced by Container Apps | Still secrets with rotation; contradicts the requirement. |
| System-assigned identities | Tied to app lifecycle; user-assigned identities can be granted access before apps exist. |
| One shared identity for all workloads | Violates least privilege. |
