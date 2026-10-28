# Tasks: M5 — Threshold alerting

## 1. Messaging infrastructure

- [x] 1.1 Add Service Bus namespace (local auth disabled), topic with duplicate detection, subscription and SQL filter rule from `alerting_subscription_filter`; verify key-based access is refused and the rule matches only the configured site with a test message
- [x] 1.2 Add ACS + Email Communication Service (Europe) with Azure-managed domain and the alert-service identity/role; verify an Entra-authenticated test email is delivered

## 2. Decoder publication

- [x] 2.1 Implement post-commit, pre-checkpoint publication with message ids and properties behind `ALERTING_PUBLISH_ENABLED`; verify with the Service Bus emulator that 50 persisted measurements yield 50 messages
- [x] 2.2 Verify the publication-failure scenario (no checkpoint, retry, no duplicate measurement)

## 3. Thresholds and alerts API

- [x] 3.1 Add Flyway migration (`alert_thresholds`, `alert_threshold_state`, `alert_events` with one-open-alert partial unique index, `alert_notifications`, grants); verify least-privilege grants with Testcontainers
- [x] 3.2 Implement threshold CRUD with validation, ETag and cancel-on-disable/delete; verify create and invalid-range scenarios and cancellation behaviour
- [x] 3.3 Implement `/devices/{id}/alerts` and `/alerts` queries; verify the open-alerts-of-a-site scenario
- [x] 3.4 Update OpenAPI/APIM and device-api README; verify Reader 403 on threshold mutations through APIM

## 4. Alert service

- [x] 4.1 Scaffold `services/alert-service` with Service Bus processor, passwordless config and KEDA scaler; verify it consumes from the subscription in the dev environment
- [x] 4.2 Implement the per-threshold state machine (open, ignore-older, idempotency, resolve) with row locking; verify unit and Testcontainers tests for open, 30-min no-flood, back-to-normal, duplicate, out-of-order and two-replicas-concurrent-first-breach scenarios
- [x] 4.3 Implement the notification outbox sender with retries, reminders and failure recording; verify the 70-minute reminder and email-outage scenarios with a fake clock and mocked email client
- [x] 4.4 Implement email templates and links; verify the alert-email-content scenario with snapshot tests
- [x] 4.5 Add metrics (alerts opened/closed, notifications sent/failed) and document the service in `services/alert-service/README.md`; verify metrics appear in Application Insights

## 5. Portal

- [x] 5.1 Add thresholds section (Editor-only dialogs) and alert history list on the device page; verify component tests for reader view and validation
- [x] 5.2 Playwright smoke test: create a threshold on a monitored device, inject an anomaly with the simulator, see the open alert; verify it passes against the environment

## 6. End-to-end verification

- [ ] 6.1 With the simulator, inject a 12 °C anomaly for 5 minutes on a monitored device with max 8 °C (in `sdp-dev`, where simulated rooms report 13–23 °C: a 35 °C anomaly above max 28 °C); verify one alert email within 2 minutes and one resolution email after the anomaly ends
- [x] 6.2 Inject the same anomaly on an unmonitored device; verify no alert and no email
- [x] 6.3 Write the M5 demo guide `docs/demo/m5-threshold-alerting.md` (incl. changing the subscription filter); verify a dry run follows it as written

## Workflow follow-up

- Archive the change after review so its specs become the baseline in `openspec/specs/`.
