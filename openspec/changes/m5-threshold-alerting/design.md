# Design: M5 — Threshold alerting

## Context

ADR-0009 fixes the topology (decoder → Service Bus topic `measurements` → filtered subscription `alerting` → alert service → ACS Email). Thresholds and alerts live in PostgreSQL (ADR-0004). See [proposal.md](proposal.md) and `specs/`.

## Goals / Non-Goals

**Goals:** correct alert lifecycle under at-least-once, out-of-order delivery and multiple replicas; demoable with simulator anomalies.

**Non-Goals:** silent-device alerts, multi-metric rules, UI for the monitoring filter, other channels.

## Decisions

### D1. Decoder publication
After the DB commit and before the checkpoint, the decoder sends the batch's measurements with `ServiceBusSenderClient.createMessageBatch()` (multiple Service Bus batches if needed), `messageId` = SHA-256 hex of `deviceId + "\n" + messageKey` (unique across devices, ≤ 128 chars), original key in property `messageKey`, application properties `deviceId`, `siteId`, `payloadFormat`, `schemaVersion=1`. Topic duplicate detection (10-minute window) absorbs re-publication after a retried batch. Decoder identity gets `Azure Service Bus Data Sender` on the topic only.

### D2. Subscription filter
Terraform variable `alerting_subscription_filter` (default `siteId = 'paris-01'`) → `azurerm_servicebus_subscription_rule` (SQL filter) replacing the default `$Default` true-filter. Subscription: lock duration 1 min, max delivery 10, TTL 1 h, dead-lettering on expiration.

### D3. Alert service
- `services/alert-service` (Spring Boot 4, no ingress). `ServiceBusProcessorClient` (peek-lock, max concurrent calls 8, prefetch 50), KEDA `azure-servicebus` scaler with managed identity (min 0/1, max 3).
- Thresholds cached in-process for 30 s per device (Caffeine) to keep DB load low; changes apply within 30 s (inside the 2-minute SLA).
- **State machine per threshold** persisted in `alert_events` (`OPEN`/`CLOSED`/`CANCELLED`) with `last_evaluated_at`. Evaluation in a DB transaction with `SELECT … FOR UPDATE` on the threshold's always-present `alert_threshold_state` row (created with the threshold; holds `last_evaluated_at`, `last_message_key`, open alert id), which serialises concurrent replicas even before any alert exists; a partial unique index on `alert_events(threshold_id) WHERE status = 'OPEN'` guarantees at most one open alert and enables "ignore older measurement" (`measured_at <= last_evaluated_at` → no state change) and idempotency (measurement key stored as `last_message_key`).
- Notifications: a row in `alert_notifications` (`PENDING`) is written in the same transaction (outbox); a scheduled sender (advisory-lock single runner) sends via ACS `EmailClient` with `DefaultAzureCredential`, exponential retries up to 30 min, then `FAILED`. This decouples email from message processing and avoids duplicate emails on redelivery.
- Reminder: the sender checks open alerts with breach still ongoing (`last_breach_at` within the last 5 min) and `last_notified_at` ≥ 60 min → reminder notification.
- Email templates: Thymeleaf text + HTML; device link built from `PORTAL_BASE_URL`.

### D4. API and data
Device-api gains `/devices/{id}/thresholds` CRUD (Write for mutations, ETag concurrency) and `/devices/{id}/alerts`, `/alerts` queries. Disabling/deleting a threshold marks the open alert `CANCELLED` in the same transaction. Grants: device-api CRUD on `alert_thresholds`, `SELECT` on alert tables, `UPDATE(status, closed_at)` on `alert_events`; alert-service `SELECT` thresholds, full DML on `alert_events`/`alert_notifications`.

### D5. Azure Communication Services
`azurerm_email_communication_service` (data location `Europe`) + Azure-managed domain + `azurerm_communication_service` linked to the domain; sender `DoNotReply@<managed-domain>`. Alert-service identity gets the least-privilege role required for Entra-authenticated email sending (custom role with `Microsoft.Communication/CommunicationServices/Read` and email send action if no built-in role fits). ACS access keys are never read or output.

### D6. Portal
Device page sections "Thresholds" (table + Editor dialogs with min/max/recipients validation) and "Alerts" (list with status badges); chart shows threshold lines for the selected metric (nice-to-have, same component).

## Risks / Trade-offs

- [Publishing every measurement costs Service Bus operations] → Standard tier with batched sends; acceptable at demo volumes (ADR-0009).
- [Azure-managed email domains have low sending limits and may land in spam] → acceptable for demo; custom domain is a configuration change.
- [Thresholds cache delays changes by up to 30 s] → within SLA; documented.
- [Decoder checkpoint now depends on Service Bus] → retries with back-off; dead-letter monitoring alert in Application Insights.

## Migration Plan

Additive migration and new resources. Enabling the publisher is controlled by a decoder feature flag (`ALERTING_PUBLISH_ENABLED`) so the decoder can be deployed before the topic exists.
