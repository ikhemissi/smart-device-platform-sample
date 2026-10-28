# alert-service

Evaluates the measurements of monitored devices against the thresholds Editors define, manages alerts and emails them through Azure Communication Services. Implements the `threshold-alerting` spec (ADR-0009, M5 design D3). It has no API: thresholds and alert history are served by [device-api](../device-api).

## How it works

```
decoder ─▶ Service Bus topic `measurements` ─▶ subscription `alerting` (SQL filter: monitored devices) ─▶ alert-service ─▶ ACS Email
                                                                                                  │
                                                                                     PostgreSQL: thresholds, alerts, outbox
```

- **Input**: the decoder publishes every persisted measurement (JSON, schema version 1; properties `deviceId`, `siteId`, `payloadFormat`; message id = SHA-256 of device id and message key). The `alerting` subscription only selects the monitored devices, with the SQL filter of the azd environment value `ALERTING_SUBSCRIPTION_FILTER` (default `siteId = 'paris-01'`).
- **Consumption**: peek-lock processor (8 concurrent messages, prefetch 50). A message is completed once evaluated; abandoned and redelivered on failure (at most 10 deliveries, then dead-lettered by Service Bus); dead-lettered at once when it can never be processed (invalid JSON, unknown schema version).
- **Thresholds**: a device's enabled thresholds are cached for 30 s, so changes made through the API apply within 30 s.
- **State machine, per threshold**: each evaluation runs in a transaction holding the lock on the threshold's `alert_threshold_state` row, so replicas and threshold changes are serialised:
  - a measurement older than the last one evaluated, or the same measurement delivered again, changes nothing;
  - outside the range, no open alert → an alert opens (with a snapshot of the threshold) and its email is queued;
  - outside the range, alert open → last and worst values are updated, no email;
  - back within the range → the alert closes and one "back to normal" email is queued.

  A partial unique index guarantees at most one open alert per threshold. Disabling or deleting a threshold (device-api) cancels its open alert without email.
- **Emails (outbox)**: emails are rows of `alert_notifications`, written in the alert's transaction, so a redelivered message never sends a second email. Every 5 s the sender claims due emails (`FOR UPDATE SKIP LOCKED`: one replica per email) and sends them; a failure is retried with exponential back-off (30 s to 5 min) for 30 minutes, then recorded as `FAILED` (visible in the alert history). Alert state never waits for emails.
- **Reminders**: while an alert is open and its breach is ongoing (last breaching measurement less than 5 minutes old), a reminder is queued when no email was sent for 60 minutes (checked every minute by one scheduler at a time, PostgreSQL advisory lock).
- **Content**: subject such as `Alert: Cold room – Air temperature above 8 °C`; body with the device (name and id), site, metric and unit, current and worst values, threshold, times (time zone `SDP_EMAIL_TIME_ZONE`) and a link to the device page. Plain text and HTML from [templates/email](src/main/resources/templates/email); examples in [snapshots](src/test/resources/snapshots).

## Metrics

Exported to Application Insights (`customMetrics`):

| Metric | Dimensions | Meaning |
|---|---|---|
| `sdp.alerts.opened` | `sdp.metric` | Alerts opened |
| `sdp.alerts.closed` | `sdp.metric` | Alerts back to normal |
| `sdp.alerts.notifications` | `sdp.notification.kind` (`ALERT`, `REMINDER`, `RESOLVED`), `sdp.notification.outcome` (`sent`, `retry`, `failed`) | Email attempts |
| `sdp.alerts.measurements` | — | Measurements evaluated |

Each evaluation is a `sdp.alert.evaluate` span continuing the decoder's trace (`traceparent`), so a measurement can be followed from the device to the alert.

## Security

- **Managed identity** `id-alert-service-*`:
  - Service Bus: *Azure Service Bus Data Receiver* on the `alerting` subscription only (local authentication is disabled on the namespace);
  - Communication Services: custom role *SDP email sender (&lt;env&gt;)* (`CommunicationServices/Read`, `CommunicationServices/Write`, `EmailServices/write`), no access key;
  - PostgreSQL: member of `sdp_alerting`: reads thresholds, sites, metric definitions and device ids/names; updates threshold evaluation state; inserts and updates alerts and notifications. It cannot change thresholds, delete alerts or read telemetry.
- Recipients' addresses are only used to send the alert emails.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `SDP_DB_URL`, `SDP_DB_USERNAME` | — | PostgreSQL JDBC URL and the principal name (Entra token authentication) |
| `SDP_SERVICEBUS_NAMESPACE` | — | Service Bus host name, `<namespace>.servicebus.windows.net` |
| `SDP_SERVICEBUS_TOPIC` / `SDP_SERVICEBUS_SUBSCRIPTION` | `measurements` / `alerting` | Topic and subscription |
| `SDP_EMAIL_ENDPOINT` | — | Communication Services endpoint |
| `SDP_EMAIL_SENDER` | — | Sender address of the Azure-managed domain, `DoNotReply@<domain>.azurecomm.net` |
| `SDP_EMAIL_TIME_ZONE` | `Europe/Paris` | Time zone of the times in emails |
| `SDP_PORTAL_BASE_URL` | `http://localhost:5173` | Portal address used in the device links |
| `AZURE_CLIENT_ID` | — | Client id of the user-assigned managed identity |

The monitored devices are changed with the azd environment value `ALERTING_SUBSCRIPTION_FILTER` (Terraform variable `alerting_subscription_filter`; empty: `siteId = 'paris-01'`), e.g. `azd env set ALERTING_SUBSCRIPTION_FILTER "siteId IN ('paris-01','lyon-01')"` then `azd provision`. Use single quotes in the filter.

## Running locally against the environment

`azd provision` grants your identity the Service Bus receiver role on the subscription and the email sender role. While running locally you compete with the deployed replica for messages.

```bash
eval "$(azd env get-values)"
export SDP_DB_URL="jdbc:postgresql://$POSTGRES_HOST:5432/$POSTGRES_DATABASE?sslmode=require" \
       SDP_DB_USERNAME="$(az ad signed-in-user show --query userPrincipalName -o tsv)" \
       SDP_SERVICEBUS_NAMESPACE="$SERVICEBUS_NAMESPACE_FQDN" \
       SDP_EMAIL_ENDPOINT="$COMMUNICATION_SERVICE_ENDPOINT" SDP_EMAIL_SENDER="$EMAIL_SENDER_ADDRESS" \
       SDP_PORTAL_BASE_URL="$WEB_URL"
./mvnw -pl services/alert-service -am spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

Tests: `AlertLifecycleIT` (state machine and emails with a fake clock: no flooding, reminders, duplicates, out-of-order, concurrent replicas, email outage), `MeasurementProcessorIT` (Service Bus emulator: subscription filter, dead-lettering), `EmailRendererTest` (email snapshots; regenerate with `-Dsdp.snapshots.update=true`).
