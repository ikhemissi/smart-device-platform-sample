# Tasks: M3 — Telemetry history and chart

## 1. Data and decoder

- [x] 1.1 Add Flyway migration for `device_metrics` and `ingestion_watermarks` with backfill; verify on Testcontainers that backfill matches the metric keys present in seeded measurements
- [x] 1.2 Extend the decoder to upsert `device_metrics` per batch and to write per-partition ingestion watermarks after each checkpoint ("now" when caught up); verify with the decoder integration test that a new metric appears after one batch and the watermark advances, and stalls while a backlog exists
- [x] 1.3 Backfill 30 days of realistic history for the demo fleet (decided with the product owner: 30-day charts are full in customer demos and performance is measured on them): move the simulator's fleet model to a shared library and add an opt-in `backfill` task to the db-migrator (past partitions, readings from the same per-device model encoded and decoded with the real codecs, `first_seen_at` and `device_metrics` updated); verify with Testcontainers that backfilled rows decode identically to live ones and that a second run adds nothing, then run it on `sdp-dev`

## 2. History API

- [x] 2.1 Implement `GET …/measurements/latest`; verify spec scenarios (latest 20 ordering, max 100, 404)
- [x] 2.2 Implement `GET …/measurements` with range validation and keyset cursor paging; verify the 1,200-row paging and 40-day scenarios
- [x] 2.3 Implement `GET …/series` (raw/bucket selection ladder, min/avg/max/count, units, empty metric); verify 1 h raw and 30 d 6 h-bucket scenarios
- [x] 2.4 Implement `GET …/metrics` with unit mapping for channel-suffixed keys; verify the Ruuvi DF5 scenario
- [x] 2.5 Measure uncached 30-day series latency on the dev environment with a 30-day synthetic dataset; verify p95 < 1.5 s at the gateway (record results in design.md)
- [x] 2.6 Update `openapi.json`, APIM import and the device-api README; verify the new operations are reachable through APIM with Read role and refused without token

## 3. Cache

- [x] 3.1 Spike Redis Entra authentication from Spring Boot 4 with the managed identity; verify a token-authenticated `PING` from the container app
- [x] 3.2 Add Azure Managed Redis (access keys disabled, access policy for device-api identity) to Terraform; verify key-based access is refused
- [x] 3.3 Implement chunked series caching with watermark-based immutability, latest/metrics caching, timeouts and circuit breaker; verify unit tests for chunk alignment/stitching, the freshness scenario, and a simulated decoder backlog (late measurement still visible within 60 s of persistence)
- [x] 3.4 Verify the cache-outage scenario (Redis unreachable → responses succeed, `sdp.cache.errors` emitted) and the "second request ≥ 3× faster" scenario

## 4. Portal device page

- [x] 4.1 Add the `/devices/:deviceId` route with device header and navigation from the list; verify component test "open from list"
- [x] 4.2 Implement metric selector (≤ 4, units, default temperature) and period selector (presets/custom, URL state); verify component tests for default selection and 7 d switch
- [x] 4.3 Implement the ECharts chart (raw lines, aggregated avg + min–max band, multi-axis, tooltips); verify visual test snapshots for raw and aggregated modes
- [x] 4.4 Implement the latest-20 table with unit headers and new-row highlight; verify component tests for ordering and highlight
- [x] 4.5 Implement auto-refresh (30 s, paused when hidden), empty and error states; verify component tests using fake timers and visibility events
- [x] 4.6 Measure in a deployed browser the 30-day chart render time for a device reporting every 30 s (cold and warm cache); verify < 3 s cold
- [x] 4.7 Playwright smoke test: open a simulated device, switch periods, see a new row within 60 s; verify it passes against the environment

## 5. Integration

- [x] 5.1 Write the M3 demo guide `docs/demo/m3-device-data-and-chart.md` (incl. showing cache hits in Application Insights); verify a dry run follows it as written

## Workflow follow-up

- Archive the change after review so its specs become the baseline in `openspec/specs/`.
