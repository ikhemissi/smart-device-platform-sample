# Spec Delta

## Purpose

Lets a presenter create, update and remove a complete, isolated platform environment with a single command, including the demo bootstrap needed for the platform to work immediately.

## ADDED Requirements

### Requirement: One-command environment lifecycle
The platform SHALL be provisioned and deployed into an empty resource group with a single `azd up` for a named environment, updated by re-running it, and removed entirely with `azd down --purge`.

#### Scenario: Fresh deployment
- **WHEN** a presenter with the required permissions runs `azd up` for a new environment
- **THEN** all resources of the delivered milestones are created, services are deployed and healthy, and the command completes without manual steps

#### Scenario: Re-deployment is idempotent
- **WHEN** `azd up` is run again without changes
- **THEN** no resource is recreated and services remain healthy

#### Scenario: Teardown
- **WHEN** `azd down --purge` is run
- **THEN** all resources of the environment, including soft-deleted ones, are removed

### Requirement: Environment isolation
Several environments SHALL coexist in the same subscription without name collisions, each resource being tagged with its environment name.

#### Scenario: Two environments
- **WHEN** environments `demo-a` and `demo-b` are deployed in the same subscription
- **THEN** both deploy successfully and neither affects the other

### Requirement: Regions
All regional resources SHALL be deployed in the configured primary region (default `francecentral`) except Static Web Apps, deployed in the configured web region (default `westeurope`). Non-regional resources (Entra objects, global resources) are exempt; services with a data location (Communication Services) SHALL use Europe, and model deployments the EU data zone.

#### Scenario: Default regions
- **WHEN** an environment is deployed with default settings
- **THEN** every regional resource is located in `francecentral` except the Static Web App located in `westeurope`, and every data location is in Europe

### Requirement: Demo bootstrap
Provisioning SHALL ensure that the database schema is at the latest version, that each configured demo site exists with a demo certificate authority, and that the corresponding device enrollment is enabled, so the simulator can send data immediately. Re-running SHALL NOT regenerate existing site authorities.

#### Scenario: Ready to ingest after deployment
- **WHEN** `azd up` completes on a new environment
- **THEN** the schema is migrated, demo sites `paris-01`, `lyon-01`, `marseille-01` exist with their certificate authorities, and a simulated device of each site can provision

#### Scenario: Bootstrap re-run keeps authorities
- **WHEN** `azd up` is run a second time
- **THEN** existing site certificate authorities are kept and devices already provisioned keep working

### Requirement: No secrets in deployment outputs
Deployment outputs, azd environment values and logs SHALL NOT contain passwords, keys, SAS tokens or connection strings.

#### Scenario: Inspect environment values
- **WHEN** `azd env get-values` is run after deployment
- **THEN** no value contains a key, password, SAS token or connection string
