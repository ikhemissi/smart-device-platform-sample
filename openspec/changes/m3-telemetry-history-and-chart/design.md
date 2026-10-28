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
- **Chunked caching**: a series request is split into aligned chunks — full UTC days for 1 h/6 h/1 d buckets, full hours for 1/5/15 min buckets and raw mode. A chunk is treated as immutable only if it ends before the **ingestion watermark** — the minimum, over Event Hubs partitions, of the enqueued time up to which the decoder has persisted everything (the decoder writes it per partition to `ingestion_watermarks` after each checkpoint: the time the partition was last observed when the batch ends at its last enqueued event (`trackLastEnqueuedEventProperties`), the enqueued time of the batch's last event while a backlog exists, and now minus two batch-wait windows after an empty batch). Partitions are registered at `-infinity` when processing starts and watermarks only move forward; the device API trusts the minimum only once all `SDP_EVENTHUB_PARTITIONS` partitions have reported, and subtracts a 2-minute margin (IoT Hub enqueue time, stored as `measured_at`, precedes the Event Hubs enqueue time used by the watermark). Immutable chunks are cached 24 h (key `sdp:v1:series:{device}:{metric}:{resolution}:{chunkStart}`); all other chunks 30 s. A decoder backlog therefore never freezes incomplete data in the cache. Results are stitched and trimmed to `[from, to)`. This keeps the hit ratio high for sliding windows and honours the 60 s freshness requirement.
- Latest measurements cached 10 s per (device, count); metric lists 5 min; range pages not cached (used rarely, mostly by the agent).
- Serialisation: compact JSON (Jackson). Cache-aside with a short Redis command timeout (100 ms) and a circuit breaker; on failure → DB, metric `sdp.cache.errors`.
- **Passwordless**: Azure Managed Redis with access keys disabled; Entra access-policy assignment for the device-api identity (and the deployer in dev); TLS port 10000; clustering policy `EnterpriseCluster` (single endpoint, no cluster-aware client needed); keys use hash tags (`{device|metric}`) so multi-key reads stay in one slot. The Lettuce client authenticates with a small streaming `RedisCredentialsProvider` built on the platform's `TokenCredential` (token for `https://redis.azure.com/.default`, user name = the token's `oid`, renewed 10 minutes before expiry; connections re-authenticate on new credentials) rather than the `redis-authx-entraid` extension, which is still a beta and would add a second credential chain. Connection initialisation (TLS + HELLO) uses a 5 s timeout, commands 100 ms; connections are made in the background so requests never wait for Redis.
*Alternative*: whole-response caching keyed by exact query — rejected; "last 24 h" windows never repeat exactly.

### D3. Reported metrics
Flyway migration adds `device_metrics(device_id, metric, last_seen_at, PRIMARY KEY (device_id, metric))`. The decoder upserts one row per (device, metric) present in each batch with `last_seen_at = GREATEST(…)`. The endpoint returns rows with `last_seen_at` within the retention period, joined with `metric_definitions` (channel-suffixed Cayenne keys `<metric>_ch<n>` map to their base metric for units). To avoid rewriting every row on every batch, `last_seen_at` is only updated when it moves forward by more than an hour. Rows older than the retention are purged by the migrator's maintenance job (the same job that applies partition retention; no `pg_cron`).

### D4. Cursor paging
Opaque cursor = base64url of `(measured_at, message_key)` of the last row; next page uses keyset predicate `(measured_at, message_key) > (:t, :k)`.

### D5. Portal device page
- Route `/devices/:deviceId`; Apache ECharts (`echarts-for-react`, tree-shaken) for performance with thousands of points, multiple y-axes, band rendering (stacked area of `min` and `max-min`), and `dataZoom`.
- TanStack Query `refetchInterval: 30_000` with `refetchIntervalInBackground: false`; table rows diffed by message key for highlight.
- URL state: `?metrics=temperature,humidity&period=7d` or `from/to` for custom.

### D6. APIM
New operations imported from the updated `openapi.json`; same policies (Read role). Response caching is **not** done in APIM (would bypass freshness control and per-user authorisation semantics).

### D7. Demo history backfill
Decided with the product owner: the long-lived demo environment gets 30 days of realistic history before the live data, so 30-day charts are meaningful in customer demos and the performance targets are measured on real-sized data.
- The simulator's fleet model (`ReadingModel`, `PayloadFactory`, `SimulatedDevice`, `Anomaly`) moves to `libs/device-fleet`, shared by the simulator and the migrator.
- New opt-in migrator task `backfill` (never part of the default tasks), started as a one-off execution of the migrator job (`scripts/backfill-history.sh`): it runs in Azure as the schema owner, so it can create the past daily partitions and load in-region. For each device in `sdp.devices` it generates readings every interval (± jitter) from `now - days` up to the device's `first_seen_at`, using the same per-device seeded model as the live simulator so levels and daily cycles join smoothly. Payloads are encoded and decoded with the real codecs, so rows are identical in shape to live ones (`message_key` = `backfill-<epoch ms>`). `first_seen_at` and `device_metrics` are updated. A second run adds nothing (the range ends at the new `first_seen_at`).
- The history cache is flushed after a backfill, because chunks cached as immutable before it would hide the new rows.

## Measurements (sdp-dev, 28 Oct 2026)

Dataset: 30 devices reporting every 30 s, 30 days of history (2.52 M backfilled + live, 1.2 GB), PostgreSQL Burstable, Azure Managed Redis Balanced B0.

| Measurement | Result | Target |
|---|---|---|
| 50 different uncached 30-day series, one metric each (`maxPoints=500` → 6 h buckets, ~86,400 measurements each), sequential, client time through APIM | p50 0.53 s, p95 0.89 s (one cold outlier at 5.4 s) | p95 < 1.5 s |
| Same 30-day series twice within a minute (5 devices), APIM request duration | 264–333 ms → 24–37 ms: 7.5× to 12.7× faster in steady state; the first pair after deployment 2.7× (connection warm-up) | ≥ 3× |
| Redis unreachable (host pointed at a non-existent name) | every request 200 from the database; `sdp.cache.errors{operation=connect}` emitted | succeeds, metric emitted |
| Key-based access | ListKeys refused (access keys disabled), anonymous `PING` → `NOAUTH` | refused |

No expression index was needed: partition pruning plus the primary key `(device_id, measured_at, …)` meet the targets.

## Risks / Trade-offs

- [JSONB extraction cost on large ranges] → bounded by device + time range and ≤ 4 metrics; expression index `(device_id, measured_at) INCLUDE (metrics)` evaluated if the p95 target is missed.
- [Redis Entra auth library maturity with Lettuce/Spring Data Redis on Spring Boot 4] → spike at the start of the milestone; fallback is a token-refreshing `RedisCredentialsProvider` built on `azure-identity`.
- [Azure Managed Redis Terraform coverage] → use `azurerm_managed_redis` if available in the pinned provider, `azapi` otherwise (incl. access policy assignment).
- [Clock skew between client "now" and server] → server computes "now" for preset periods when `to` is omitted.

## Migration Plan

Additive migration (`device_metrics` and `ingestion_watermarks`, backfilled by a one-off SQL in the migration from retained measurements). Decoder and device-api deployed after the migration (azd postprovision ordering from M1).
