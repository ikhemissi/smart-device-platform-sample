# telemetry-decoder

Consumes device telemetry routed by IoT Hub to Event Hubs, decodes it in batches with [`payload-codecs`](../../libs/payload-codecs/README.md) and stores it in PostgreSQL. Implements the `telemetry-ingestion` spec of OpenSpec change `m1-device-data-ingestion` (ADR-0002, ADR-0003, ADR-0004).

## How it works

1. An Event Hubs **event processor** (consumer group `telemetry-decoder`, checkpoints in Blob Storage) delivers batches per partition (up to `SDP_BATCH_MAX_SIZE` events or `SDP_BATCH_MAX_WAIT`).
2. Each event is mapped to a message: device id = `iothub-connection-device-id`, measurement time = `iothub-enqueuedtime`, site = `siteId` application property (IoT Hub enrichment from the device twin), message key = device message id (or `<partition>:<sequenceNumber>`), trace context = `traceparent` property.
3. Each payload is decoded independently. Undecodable messages, messages without device (`MISSING_DEVICE`) or without a valid site (`MISSING_SITE`) become **rejected messages** with a reason, and never block the batch.
4. One transaction: create unknown sites → insert `(device, message key)` into the deduplication ledger → insert only the measurements/rejections whose key is new → register/update devices (`last_seen_at`, `last_payload_format`).
5. Only then is the batch **checkpointed**. If the database is unavailable, persistence is retried with exponential back-off (1 s → 30 s) and the batch is not checkpointed; on shutdown an unpersisted batch is abandoned and redelivered from the last checkpoint. Delivery is at-least-once, storage is exactly-once per (device, message key).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `SDP_EVENTHUBS_NAMESPACE` | — | Event Hubs namespace FQDN, e.g. `evhns-demo.servicebus.windows.net` (managed identity) |
| `SDP_EVENTHUB_NAME` | `device-telemetry` | Event hub |
| `SDP_EVENTHUBS_CONSUMER_GROUP` | `telemetry-decoder` | Consumer group |
| `SDP_CHECKPOINT_CONTAINER_URL` | — | Checkpoint container URL, e.g. `https://stdemo.blob.core.windows.net/checkpoints` (managed identity) |
| `SDP_BATCH_MAX_SIZE` / `SDP_BATCH_MAX_WAIT` | `500` / `5s` | Batch size and maximum wait |
| `SDP_DB_URL`, `SDP_DB_USERNAME` | — | PostgreSQL JDBC URL and the decoder principal name (Entra token authentication) |
| `SDP_DB_PASSWORDLESS` / `SDP_DB_PASSWORD` | `true` / — | Password authentication for local databases only |
| `SDP_DB_POOL_SIZE` | `6` | Connection pool size |
| `AZURE_CLIENT_ID` | — | Client id of the decoder's user-assigned managed identity |
| `SDP_EVENTHUBS_EMULATOR_CONNECTION_STRING` | — | **Local only**: Event Hubs emulator |
| `SDP_CHECKPOINT_AZURITE_CONNECTION_STRING`, `SDP_CHECKPOINT_CONTAINER` | — / `checkpoints` | **Local only**: Azurite |

All `sdp.decoder.*` properties are defined in [application.yaml](src/main/resources/application.yaml).

## Operations

- **Health** (port `8080`): `/actuator/health/liveness`, `/actuator/health/readiness`. Readiness includes the `ingestion` indicator, `DOWN` (with `failingSince` and `lastError`) while batches cannot be persisted.
- **Scaling**: one replica per Event Hubs partition at most (KEDA Event Hubs scaler); partitions are balanced automatically between replicas.
- **Telemetry** (OpenTelemetry → Application Insights): span `sdp.ingest.batch` per batch with one child span `sdp.ingest.message` per message (attributes `sdp.device.id`, `sdp.site.id`, `sdp.payload.format`, `sdp.ingestion.outcome`, `sdp.rejection.reason`), children of the Event Hubs SDK's `process device-telemetry` request, which links to each message's send span (`_MS.links`). `scripts/trace-device.sh <deviceId>` shows the whole journey. Metrics: `sdp.ingestion.messages{sdp.payload.format, sdp.ingestion.outcome, sdp.rejection.reason}`, `sdp.ingestion.batch.size`, `sdp.ingestion.latency` (IoT Hub enqueue → persisted).
- **Investigating rejections**: `SELECT received_at, device_id, reason, detail, encode(raw_payload, 'hex') FROM sdp.rejected_messages ORDER BY received_at DESC LIMIT 50;`

## Tests

- `./mvnw -pl services/telemetry-decoder test`: mapping, decoding/classification, retry/back-off/health, checkpoint ordering, metrics and spans (in-memory OpenTelemetry).
- `./mvnw -pl services/telemetry-decoder -am verify`: also `JdbcTelemetryStoreIT` (PostgreSQL 18, connected **as the decoder principal** with its real grants) and `DecoderPipelineIT` (Event Hubs emulator + Azurite + PostgreSQL: end-to-end ingestion, checkpoints only after persistence, database outage without loss or duplicates). See the root README for Podman settings.
