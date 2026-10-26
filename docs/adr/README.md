# Architecture Decision Records

An ADR records an architectural decision that is **hard to reverse**: changing it later would mean rewriting several services, migrating data, re-issuing device credentials, re-deploying infrastructure, or breaking a contract with devices, users or other systems. Each ADR states explicitly **why it is hard to reverse**.

Everything else (libraries, tunables, naming, SKUs, conventions, operational details) is reversible and lives in the [implementation guidelines](../architecture/implementation-guidelines.md) or in the OpenSpec `design.md` of the change that introduces it.

Format: Context, Decision, Why hard to reverse, Consequences, Alternatives considered. Statuses: `Proposed` → `Accepted` → (`Superseded by ADR-XXXX` | `Deprecated`). An accepted ADR is never edited in substance; it is superseded by a new one.

| ADR | Title | Status |
|---|---|---|
| [ADR-0001](ADR-0001-runtime-and-languages.md) | Programming languages and hosting platform | Proposed |
| [ADR-0002](ADR-0002-ingestion-pipeline.md) | Telemetry ingestion pipeline: IoT Hub → Event Hubs → batch decoder | Proposed |
| [ADR-0003](ADR-0003-device-payload-formats.md) | Device payload formats and leading-byte format detection | Proposed |
| [ADR-0004](ADR-0004-telemetry-data-model.md) | Telemetry storage model in PostgreSQL | Proposed |
| [ADR-0005](ADR-0005-passwordless-identity.md) | Passwordless identity model | Proposed |
| [ADR-0006](ADR-0006-device-identity-per-site-ca.md) | Device identity: X.509 CA per site through DPS enrollment groups | Proposed |
| [ADR-0007](ADR-0007-user-authn-authz-and-api-exposure.md) | User authorization model and API exposure | Proposed |
| [ADR-0008](ADR-0008-infrastructure-as-code.md) | Infrastructure as code, regions and network exposure | Proposed |
| [ADR-0009](ADR-0009-alerting-service-bus.md) | Alerting through a Service Bus topic with filtered subscriptions | Proposed |
| [ADR-0010](ADR-0010-ai-agent.md) | AI agent framework and data-access model | Proposed |
