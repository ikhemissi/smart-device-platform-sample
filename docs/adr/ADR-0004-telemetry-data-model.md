# ADR-0004 — Telemetry storage model in PostgreSQL

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

All data is stored in Azure Database for PostgreSQL – Flexible Server. Devices of different formats report different metric sets; users need the latest N measurements per device, time-range queries and chart aggregates over at least 30 days.

## Decision

1. **One row per received message** in a `measurements` table holding the device, site, measurement time, payload format, the **original raw payload**, and the decoded **canonical metrics as `jsonb`** (plus optional textual attributes as `jsonb`).
2. **The table is range-partitioned by measurement time** (native PostgreSQL declarative partitioning); retention is applied by dropping partitions.
3. Relational reference data (sites, devices, thresholds, alerts, certificates metadata) lives in regular tables in the same database.

## Why hard to reverse

The table shape and partitioning strategy determine every query, index and API that reads history; changing them on a populated table requires a full data migration. Keeping the raw payload is a one-way door in the other direction: history without raw payloads can never be re-decoded.

## Consequences

- Format-agnostic: new formats/metrics need no schema change; "latest N measurements" maps 1:1 to rows.
- Metric extraction from `jsonb` is less efficient than a columnar/narrow layout at very large scale — acceptable for the demonstrator.
- Queries must always be time-bounded to benefit from partition pruning.
- Idempotency cannot rely on a unique constraint without the partition key; a separate deduplication mechanism is needed (see implementation guidelines).

## Alternatives considered

| Option | Why not |
|---|---|
| TimescaleDB hypertables | Native partitioning explicitly preferred. |
| Narrow table (one row per metric value) | 5–15× more rows; "latest N measurements" requires pivoting. |
| Wide table (one column per metric) | Every new format/metric requires a migration; mostly null columns. |
| Non-PostgreSQL time-series store | Requirement: all data in PostgreSQL. |
