# db-migrator

One-shot job that owns the database schema. It runs Flyway migrations from `libs/db-schema` and database maintenance, then exits: exit code `0` on success, non-zero on failure. In Azure it runs as a Container Apps job under the **migrator managed identity**, which is the server's Entra administrator and owns the schema; application identities only get least-privilege grants.

## Tasks

| Task | What it does |
|---|---|
| `migrate` | Creates the workload principals (through the `postgres` database: on Azure with `pgaadauth`, as Entra principals), applies `db/migration` (versioned SQL, repeatable Java migration seeding `sdp.metric_definitions` from the codec catalogue), then grants each principal its group role |
| `maintain` | Re-applies the retention to partitioned tables, runs `partman.run_maintenance_proc()` (pre-creates daily partitions for the next 7 days, drops expired ones), purges deduplication keys older than the ledger retention, and deletes anomalies, site digests and closed (done or resolved) maintenance items older than the retention |

The deployment runs `migrate,maintain` after every provisioning, and `maintain` hourly on a schedule.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `SDP_DB_URL` | `jdbc:postgresql://localhost:5432/sdp?sslmode=require` | JDBC URL |
| `SDP_DB_USERNAME` | — | Entra principal name (managed identity or your UPN), or a local user |
| `SDP_DB_PASSWORDLESS` | `true` | Entra token authentication (`DefaultAzureCredential`; uses `AZURE_CLIENT_ID` for the managed identity) |
| `SDP_DB_PASSWORD` | — | Local password authentication only (`SDP_DB_PASSWORDLESS=false`) |
| `SDP_MIGRATOR_TASKS` | `migrate,maintain` | Tasks to run |
| `SDP_RETENTION` | `30d` | Retention of measurements and rejected messages (minimum 30 days) |
| `SDP_LEDGER_RETENTION` | `7d` | Retention of deduplication keys |
| `SDP_DECODER_PRINCIPAL_NAME` / `_OBJECT_ID` | — | Decoder managed identity name and object (principal) id; skipped when empty |

## Database model and privileges

- Schema `sdp` (owner: migrator). `measurements` and `rejected_messages` are range-partitioned by day with pg_partman (schema `partman`); `ingested_messages` is the non-partitioned deduplication ledger (ADR-0004, implementation guidelines §3).
- Group roles (NOLOGIN) carry privileges; principals are members. On Azure, principals are created with `pgaadauth_create_principal_with_oid` from the `postgres` database (the only one where `pgaadauth` exists); a same-named plain role is replaced. On plain PostgreSQL principals are `LOGIN` roles.

| Group role | Members | Privileges |
|---|---|---|
| `sdp_ingest` | decoder identity | `INSERT` measurements, rejected messages, ledger (+ `SELECT` of ledger keys for `RETURNING`); `SELECT, INSERT` sites and devices; `UPDATE(last_seen_at, last_payload_format)` on devices. No DDL, no reads of telemetry, no deletes. |

## Running locally

```bash
scripts/local-postgres.sh start          # PostgreSQL 18 + pg_partman in Podman/Docker on localhost:55432
scripts/run-migrations.sh --local        # migrate + maintain with password auth
scripts/run-migrations.sh --local --tasks maintain
scripts/local-postgres.sh stop
```

Against an Azure dev environment (your Entra identity must be an Entra administrator of the server):

```bash
az login
scripts/run-migrations.sh                # reads POSTGRES_HOST, POSTGRES_DATABASE, DECODER_IDENTITY_* from azd
```

## Tests

`./mvnw -pl services/db-migrator -am verify` runs the integration tests (`*IT`) against PostgreSQL 18 + pg_partman in a container (see the root README for the Podman settings): schema and seed, daily partitions, retention dropping expired partitions, ledger purge, idempotency, and least-privilege checks for the decoder principal.
