# ADR-0008 — Infrastructure as code, regions and network exposure

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

All resources must be deployed with Terraform, ideally via the Azure Developer CLI (azd), from scratch by a presenter. Data must stay in Europe. The demonstrator has no private-network requirement.

## Decision

1. **All Azure resources and Entra app registrations are defined in Terraform and deployed through azd** (`infra.provider: terraform`); azd also builds and deploys every service.
2. **Primary region `francecentral`** for every regional resource. **Static Web Apps in `eastus2`**: its only European region (`westeurope`) is blocked by tenant policy; the Static Web Apps region only holds resource metadata and the static UI assets (no customer data), content being served globally. Services with a data location use **Europe**; model deployments use the **EU data zone**. (`swedencentral` was preferred but does not offer IoT Hub/DPS.)
3. **Public network endpoints** protected by Entra authentication and disabled local auth; no VNet integration or private endpoints.

## Why hard to reverse

Switching IaC tooling means rewriting all infrastructure code and migrating state. Most Azure resources cannot be moved between regions — a region change means re-creating resources and migrating data. Moving to private networking requires a different Container Apps environment, private endpoints for every data service, DNS, and an APIM tier change.

## Consequences

- One command deploys infrastructure and code; environments are isolated by azd environment name.
- The deployer needs Entra permissions to create app registrations.
- Container Apps ingress is publicly reachable, so backends must validate tokens ([ADR-0007](ADR-0007-user-authn-authz-and-api-exposure.md)).
- Some recent resources may need the `azapi` provider until `azurerm` supports them.

## Alternatives considered

| Option | Why not |
|---|---|
| Bicep with azd | Requirement states Terraform. |
| Terraform without azd | Loses one-command provisioning + deployment. |
| `swedencentral` | IoT Hub and DPS unavailable. |
| Static Web Apps in `westeurope` | Blocked by tenant policy (region access restriction). |
| Private networking to satisfy the tenant's public-network policies | Kept out of scope; resources carry the tenant's opt-out tags instead (implementation guidelines §7). |
| Private networking | Significant cost/complexity for a demonstrator. |
