# ADR-0002 — Telemetry ingestion pipeline: IoT Hub → Event Hubs → batch decoder

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

Devices send small binary messages that must be authenticated per device, decoded in batches and persisted, with the ability to add other consumers later (alerting, analytics).

## Decision

1. **Azure IoT Hub** is the only device-facing endpoint.
2. **IoT Hub message routing forwards device telemetry to a dedicated Azure Event Hubs namespace**; consumers never read IoT Hub's built-in endpoint. Each message carries the device's **site** (added by IoT Hub from the device twin).
3. **A single decoder workload consumes Event Hubs in batches**, decodes and persists measurements, and is the **only component that decodes payloads**. Downstream consumers (e.g. alerting, [ADR-0009](ADR-0009-alerting-service-bus.md)) receive decoded measurements from the decoder, never raw payloads.
4. **Delivery semantics are at-least-once with idempotent persistence**; a message that cannot be decoded is quarantined and never blocks the stream.

## Why hard to reverse

The device-facing endpoint is embedded in device configuration and provisioning. The Event Hubs partition count is fixed at creation and bounds consumer parallelism. Every downstream consumer is built on the "decoded once, at-least-once" contract; changing the topology or the delivery semantics affects all of them.

## Consequences

- Stream retention, partitions and consumer groups are controlled independently of IoT Hub limits; one more resource to pay for.
- Ordering is only guaranteed per partition; nothing relies on global ordering.
- All consumers must be idempotent.

## Alternatives considered

| Option | Why not |
|---|---|
| Consumers read the IoT Hub built-in endpoint | Contradicts the requirement; couples consumers to IoT Hub settings. |
| Per-message processing (Event Grid / Service Bus) | Weaker batch throughput for telemetry streams. |
| Stream Analytics for decoding | Multi-vendor binary decoding is impractical and hard to test. |
| Exactly-once (transactional outbox end to end) | Unneeded complexity; idempotency gives the same observable result. |
