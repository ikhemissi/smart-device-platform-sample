# ADR-0009 — Alerting through a Service Bus topic with filtered subscriptions

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

Email alerts must be raised by a **separate alert service** when a measurement crosses a threshold, **only for a subset of devices** selected with **Service Bus subscriptions** fed by the decoder. Emails are sent with Azure Communication Services (ACS).

## Decision

1. **The decoder publishes every persisted measurement to a Service Bus topic** as a decoded-measurement message whose **device id, site id and payload format are exposed as filterable message properties**, with a message id unique across devices. This message is a **versioned integration contract** open to future consumers.
2. **Device selection for alerting is expressed as SQL filter rules on the alerting subscription** (by site and/or device), managed as configuration.
3. **A dedicated alert service** consumes that subscription, evaluates thresholds stored in PostgreSQL, and **sends emails through Azure Communication Services Email**.

## Why hard to reverse

The published measurement message (body, properties, id semantics) becomes a contract for every subscriber; changing the broker or message shape breaks all of them at once. Device selection by subscription filters puts the "who is monitored" decision in the messaging layer; moving it elsewhere changes the decoder, the alert service and operations.

## Consequences

- Other consumers can add their own subscriptions without touching the decoder.
- Every measurement is published even if no subscription matches; cost grows with telemetry volume.
- Changing the monitored subset is a configuration change, not a UI action (consistent with PRD-005).
- The decoder depends on Service Bus availability to make progress.

## Alternatives considered

| Option | Why not |
|---|---|
| Threshold evaluation inside the decoder | Requirement asks for a separate service and Service Bus subscriptions. |
| Second Event Hubs consumer group | No server-side filtering per subscriber. |
| Event Grid namespace topics | Possible, but Service Bus SQL filters, dead-lettering and duplicate detection fit better. |
| Azure Monitor alerts | Not per-device business thresholds; email content not controllable. |
