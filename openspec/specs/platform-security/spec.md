# platform-security Specification

## Purpose

Guarantees that every workload and device accesses the platform without passwords or shared keys, with least privilege, and that the few platform-imposed exceptions are explicit and contained.

## Requirements

### Requirement: Passwordless workload access
Every platform workload SHALL authenticate to Azure services with its own Microsoft Entra managed identity; no workload SHALL use a password, access key, SAS token or connection string containing a secret.

#### Scenario: Workload configuration contains no secret
- **WHEN** the configuration and environment variables of every container app are inspected
- **THEN** none contains a password, key, SAS token or secret-bearing connection string

### Requirement: Local authentication disabled on data services
Key- or password-based authentication SHALL be disabled on PostgreSQL, Event Hubs, Storage, Key Vault (RBAC mode), Application Insights ingestion and every data service added by later milestones that supports it.

#### Scenario: PostgreSQL password login refused
- **WHEN** a client attempts to connect to PostgreSQL with a username and password
- **THEN** the connection is refused

#### Scenario: Event Hubs key access refused
- **WHEN** a client attempts to send to Event Hubs with a SAS key
- **THEN** the request is refused

### Requirement: Least privilege per workload
Each workload identity SHALL hold only the roles and database privileges it needs; in particular the decoder cannot read device certificates and application identities cannot alter the database schema.

#### Scenario: Decoder cannot change schema
- **WHEN** the decoder identity attempts to create or drop a table
- **THEN** the database refuses the statement

### Requirement: Device certificate authentication
Devices SHALL authenticate exclusively with X.509 certificates; symmetric-key device authentication SHALL NOT be used by any enrollment.

#### Scenario: No symmetric key enrollments
- **WHEN** the provisioning service enrollments are listed
- **THEN** every enrollment uses X.509 attestation

### Requirement: Documented exceptions
Any credential that cannot be avoided because of a platform limitation (the provisioning service's link to IoT Hub) SHALL be documented, never handled by a person, never output by the deployment, and never used by a platform workload.

#### Scenario: Exception is contained
- **WHEN** container app configurations, azd environment values and deployment outputs are inspected
- **THEN** none references an IoT Hub shared access policy or its keys, and the exception is listed in the security documentation
