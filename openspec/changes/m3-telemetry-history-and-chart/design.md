# Design: M3 — Telemetry history and chart

## Context

M1 stores one row per message with `metrics jsonb`, time partitioning (ADR-0004) — daily partitions and PK `(device_id, measured_at, message_key)` per the implementation guidelines §3. M2 delivered the device API, APIM and the portal shell. See [proposal.md](proposal.md) and `specs/`.

## Goals / Non-Goals

**Goals:**
- Fast chart queries over 30 days with a high cache hit ratio despite sliding "last N" windows.
- Reusable history endpoints that the AI agent (M6) can call as tools.

**Non-Goals:**
- Multi-device comparison, export, downsampled materialised views.

## Decisions

### D1. Series query
`date_bin(:bucket, measured_at, TIMESTAMPTZ '2000-01-01')` grouped per bucket with `min/avg/max/count` of `(metrics->>:metric)::float8`, filtered by `device_id` and the time range (partition pruning). Raw mode first runs a bounded `count(*)` (`LIMIT maxPoints+1` sub-select) to choose raw vs bucketed. Bucket chosen from the fixed ladder so buckets align across requests.
*Alternative*: continuous aggregates / rollup tables — rejected for now; measured performance target is reachable with partition pruning on a Burstable server; can be added later without API change.

### D2. Cache design (Azure Managed Redis)
- **Chunked caching**: a series request is split into aligned chunks — full UTC days for 1 h/6 h/1 d buckets, full hours for 1/5/15 min buckets and raw mode. A chunk is treated as immutable only if it ends before the **ingestion watermark** — the minimum, over Event Hubs partitions, of the enqueued time up to which the decoder has persisted everything (the decoder writes it per partition to `ingestion_watermarks` after each checkpoint, and sets it to "now" when a partition is fully caught up, using `trackLastEnqueuedEventProperties`). Immutable chunks are cached 24 h (key `sdp:v1:series:{device}:{metric}:{resolution}:{chunkStart}`); all other chunks 30 s. A decoder backlog therefore never freezes incomplete data in the cache. Results are stitched and trimmed to `[from, to)`. This keeps the hit ratio high for sliding windows and honours the 60 s freshness requirement.
- Latest measurements cached 10 s per (device, count); metric lists 5 min; range pages not cached (used rarely, mostly by the agent).
- Serialisation: compact JSON (Jackson). Cache-aside with a short Redis command timeout (100 ms) and a circuit breaker; on failure → DB, metric `sdp.cache.errors`.
- **Passwordless**: Azure Managed Redis with access keys disabled; Entra access-policy assignment for the device-api identity; Lettuce client with the Redis Entra ID authentication extension (`redis-authx-entraid`) using the managed identity; TLS port 10000; clustering policy `EnterpriseCluster` (single endpoint, no cluster-aware client needed).
*Alternative*: whole-response caching keyed by exact query — rejected; "last 24 h" windows never repeat exactly.

### D3. Reported metrics
Flyway migration adds `device_metrics(device_id, metric, last_seen_at, PRIMARY KEY (device_id, metric))`. The decoder upserts one row per (device, metric) present in each batch with `last_seen_at = GREATEST(…)`. The endpoint returns rows with `last_seen_at` within the retention period, joined with `metric_definitions` (channel-suffixed Cayenne keys map to their base metric for units); `pg_cron` deletes rows older than the retention.

### D4. Cursor paging
Opaque cursor = base64url of `(measured_at, message_key)` of the last row; next page uses keyset predicate `(measured_at, message_key) > (:t, :k)`.

### D5. Portal device page
- Route `/devices/:deviceId`; Apache ECharts (`echarts-for-react`, tree-shaken) for performance with thousands of points, multiple y-axes, band rendering (stacked area of `min` and `max-min`), and `dataZoom`.
- TanStack Query `refetchInterval: 30_000` with `refetchIntervalInBackground: false`; table rows diffed by message key for highlight.
- URL state: `?metrics=temperature,humidity&period=7d` or `from/to` for custom.

### D6. APIM
New operations imported from the updated `openapi.json`; same policies (Read role). Response caching is **not** done in APIM (would bypass freshness control and per-user authorisation semantics).

## Risks / Trade-offs

- [JSONB extraction cost on large ranges] → bounded by device + time range and ≤ 4 metrics; expression index `(device_id, measured_at) INCLUDE (metrics)` evaluated if the p95 target is missed.
- [Redis Entra auth library maturity with Lettuce/Spring Data Redis on Spring Boot 4] → spike at the start of the milestone; fallback is a token-refreshing `RedisCredentialsProvider` built on `azure-identity`.
- [Azure Managed Redis Terraform coverage] → use `azurerm_managed_redis` if available in the pinned provider, `azapi` otherwise (incl. access policy assignment).
- [Clock skew between client "now" and server] → server computes "now" for preset periods when `to` is omitted.

## Migration Plan

Additive migration (`device_metrics` and `ingestion_watermarks`, backfilled by a one-off SQL in the migration from retained measurements). Decoder and device-api deployed after the migration (azd postprovision ordering from M1).
