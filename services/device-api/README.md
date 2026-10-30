# device-api

Platform API for the device catalog, telemetry history and alerting: the signed-in user's permissions, devices, sites, measurements, chart series, alert thresholds and alert history. It is published through API Management at `https://<apim>.azure-api.net/api/v1` and implements the `device-catalog`, `user-access`, `telemetry-history` and `threshold-alerting` specs (ADR-0007, ADR-0009).

## Endpoints

| Method and path | Permission | Description |
|---|---|---|
| `GET /api/v1/me` | any signed-in user | Display name, user principal name, object id, `canRead`, `canWrite` |
| `GET /api/v1/devices` | `DeviceData.Read` | Page of devices with `total`. Query: `siteId`, `status` (`reporting`\|`silent`), `search` (id or display name, case-insensitive), `sort` (`displayName`\|`siteId`\|`firstSeen`\|`lastSeen`), `order` (`asc`\|`desc`), `page` (1-based), `pageSize` (default 25, maximum 100) |
| `GET /api/v1/devices/{deviceId}` | `DeviceData.Read` | Device with description, location, last change (`updatedAt`, `updatedBy`) and an `ETag` header |
| `PATCH /api/v1/devices/{deviceId}` | `DeviceData.Write` | JSON Merge Patch (`application/merge-patch+json`) of `displayName` (≤ 100), `description` (≤ 1000), `location` (`latitude`, `longitude`, `label` ≤ 100). `If-Match` with the current ETag is required: missing → 428, outdated → 412. Other fields → 400 "is not editable" |
| `GET /api/v1/sites` | `DeviceData.Read` | Sites with their number of devices |
| `GET /api/v1/devices/{deviceId}/measurements/latest` | `DeviceData.Read` | The `count` most recent measurements (default 20, maximum 100), most recent first: time, payload format, metrics, attributes |
| `GET /api/v1/devices/{deviceId}/measurements` | `DeviceData.Read` | Measurements with `from` ≤ time < `to` (at most 31 days), ascending, paged by an opaque `cursor` (`pageSize` default 500, maximum 1,000; `nextCursor` is null on the last page) |
| `GET /api/v1/devices/{deviceId}/series` | `DeviceData.Read` | Chart series of 1–4 `metrics` from `from` to `to` (default now, at most 31 days). Raw points when the range holds at most `maxPoints` (default 500, maximum 2,000) measurements, otherwise min/avg/max/count buckets of the smallest of 1m, 5m, 15m, 1h, 6h, 1d giving at most `maxPoints` buckets; `resolution` and `bucketSeconds` state which. Buckets are aligned on a fixed UTC grid |
| `GET /api/v1/devices/{deviceId}/metrics` | `DeviceData.Read` | Metrics the device reported within the retention period, with unit and description (channel-suffixed Cayenne keys such as `temperature_ch2` use the base metric's unit) |
| `GET /api/v1/devices/{deviceId}/thresholds` | `DeviceData.Read` | The device's alert thresholds, each with its `etag` and the id of its open alert, if any |
| `POST /api/v1/devices/{deviceId}/thresholds` | `DeviceData.Write` | Adds a threshold: a `metric` the device reports, `min` and/or `max` (min < max), 1–10 `recipients` (email addresses), `enabled` (default true). At most 20 per device (409 beyond) |
| `PUT /api/v1/devices/{deviceId}/thresholds/{thresholdId}` | `DeviceData.Write` | Replaces a threshold; `If-Match` with its entity tag is required (missing → 428, outdated → 412). Disabling it or changing its metric closes its open alert without email |
| `DELETE /api/v1/devices/{deviceId}/thresholds/{thresholdId}` | `DeviceData.Write` | Deletes a threshold (204) and closes its open alert without email; its past alerts are kept. `If-Match` is optional |
| `GET /api/v1/devices/{deviceId}/alerts` | `DeviceData.Read` | The device's alerts open at some point between `from` (default 30 days ago) and `to` (default now; at most 92 days), most recent first (`limit` default 100, maximum 500) |
| `GET /api/v1/alerts` | `DeviceData.Read` | Alerts of all devices, filtered by `status` (`open`, `closed`, `cancelled`) and `siteId`, same period and limit |
| `GET /api/v1/maintenance` | `DeviceData.Read` | Maintenance queue (M9), most likely first: `siteId`, `status` (repeated or comma-separated; default `open,planned`), `limit` (≤ 200). Empty when maintenance is off |
| `GET /api/v1/devices/{deviceId}/maintenance` | `DeviceData.Read` | `{enabled, assessment, item}`: the device's latest battery forecast, signals and probability, and its queue item; `enabled` is false when maintenance is off or does not assess the site |
| `POST /api/v1/maintenance/{itemId}/status` | `DeviceData.Write` | `{status: planned \| done, comment?}` (comment ≤ 1000); 409 for a `done` or `resolved` item. Settings `SDP_MAINTENANCE_ENABLED`, `SDP_MAINTENANCE_SITES` |

- A device is `reporting` when it was last seen within `SDP_REPORTING_WINDOW` (15 minutes), `silent` otherwise.
- The ETag covers only the editable fields and the last change, so incoming telemetry does not invalidate an edit in progress.
- An alert carries the threshold as it was when the alert opened (`thresholdMin`, `thresholdMax`), its `direction` (`ABOVE`/`BELOW`), status (`OPEN`, `CLOSED` when back to normal, `CANCELLED` when its threshold was disabled or deleted), opened/closed times, first, worst and last values, and its emails (`ALERT`, `REMINDER`, `RESOLVED`; `PENDING`, `SENT` or `FAILED`). Alerts are evaluated and emailed by the [alert service](../alert-service); threshold changes reach it within 30 seconds.
- Errors are RFC 9457 problem details (`application/problem+json`); validation errors list the messages per field in `errors`.

## History cache

History reads go through a cache-aside layer in Azure Managed Redis (M3 design D2):
- **Series chunks:** series are cached per device, metric and resolution in aligned chunks (1 hour for raw points and minute buckets, 1 UTC day otherwise).
  - A chunk that ends before the **ingestion watermark** cannot change any more, so it is kept for 24 hours. The watermark is the lowest per-partition time up to which the decoder has persisted everything, minus 2 minutes.
  - Other chunks are kept for 30 seconds, so a measurement is visible at most a minute after it is stored, even during a decoder backlog.
- **Other caches:** latest measurements for 10 seconds, reported metrics for 5 minutes.
- **Authentication:** Microsoft Entra ID only (access keys are disabled). The token from the service's managed identity is renewed before expiry, and connections re-authenticate.
- **Degradation:** commands time out after 100 ms. After 5 consecutive failures a circuit breaker bypasses the cache for 30 seconds. The service starts and serves without Redis.
- **Metrics:** `sdp.cache.lookups{sdp.cache.outcome=hit|miss}` and `sdp.cache.errors{sdp.cache.operation}`.

After loading history directly into the database (`scripts/backfill-history.sh`), the cache is flushed so no chunk cached as complete hides the new rows.

The OpenAPI 3.0 document is [openapi.json](openapi.json). API Management imports it, and each operation's `x-sdp-required-role` (`authenticated`, `read` or `write`) generates the gateway's role check.

## Security

- **Tokens**: Microsoft Entra ID v2 access tokens for the platform API. The issuer must be the tenant, the audience the API client id or identifier URI, and the signature is checked against the tenant's keys. Validation is done by the shared auto-configuration in [`platform-common`](../../libs/platform-common).
- **Defence in depth**: Container Apps ingress is reachable directly, so the service validates tokens and roles itself, in addition to the gateway (a direct call without a token gets 401).
- **Permissions**:
  - app roles `DeviceData.Read` and `DeviceData.Write`, where Write implies Read;
  - each controller method declares `@RequiredRole`;
  - a platform handler without the annotation is refused, so the check fails closed.
- **Database**: the managed identity `id-device-api-*` is a member of `sdp_catalog`, which has:
  - `SELECT` on sites, devices, measurements and metric definitions;
  - `UPDATE` only on the editable device columns and the audit columns;
  - thresholds: full access to `alert_thresholds` and their evaluation state (locked during a change, so it is serialised with the alert service);
  - alerts: `SELECT`, and `UPDATE` of `status` and `closed_at` only (cancellation);
  - maintenance: `SELECT` on assessments and items, and `UPDATE` of the items' status columns only (`status`, `status_by`, `status_at`, `status_comment`, `updated_at`).

  Edits record the editor's object id (`updated_by`), display name (`updated_by_name`) and time.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `SDP_ENTRA_TENANT_ID` | — | Tenant whose tokens are accepted |
| `SDP_API_AUDIENCES` | — | Comma-separated accepted audiences (API client id, `api://<client id>`) |
| `SDP_DB_URL`, `SDP_DB_USERNAME` | — | PostgreSQL JDBC URL and the principal name (Entra token authentication) |
| `SDP_DB_PASSWORDLESS` / `SDP_DB_PASSWORD` | `true` / — | Password authentication for local databases only |
| `SDP_DB_POOL_SIZE` | `10` | Connection pool size |
| `SDP_REPORTING_WINDOW` | `15m` | Reporting/silent threshold |
| `SDP_REDIS_HOST` / `SDP_REDIS_PORT` | — / `10000` | Azure Managed Redis; the history cache is disabled when the host is empty |
| `SDP_EVENTHUB_PARTITIONS` | `4` | Event Hubs partitions: the ingestion watermark is trusted once every partition has reported |
| `SDP_RETENTION` | `30d` | Measurement retention (metrics reported within it are listed) |
| `AZURE_CLIENT_ID` | — | Client id of the user-assigned managed identity |

## Running locally against the environment's database

The service connects with your own Entra identity, which needs network access to the server; the developer firewall rule is created by `azd provision`:

```bash
eval "$(azd env get-values)"
export SDP_DB_URL="jdbc:postgresql://$POSTGRES_HOST:5432/$POSTGRES_DATABASE?sslmode=require" \
       SDP_DB_USERNAME="$(az ad signed-in-user show --query userPrincipalName -o tsv)" \
       SDP_ENTRA_TENANT_ID="$ENTRA_TENANT_ID" SDP_API_AUDIENCES="$API_CLIENT_ID,api://$API_CLIENT_ID" \
       SDP_REDIS_HOST="$REDIS_HOST"   # optional: your identity has a Redis access policy in dev
./mvnw -pl services/device-api -am spring-boot:run
curl -H "Authorization: Bearer $(az account get-access-token --scope "$API_SCOPE" --query accessToken -o tsv)" \
     http://localhost:8080/api/v1/devices?pageSize=5
```

## Tests

- `./mvnw -pl services/device-api test`:
  - merge-patch rules and ETag behaviour;
  - history caching (chunk alignment and stitching, reuse, lifetimes around the watermark, a late measurement during a decoder backlog);
  - the OpenAPI document. `OpenApiDocumentTest` fails when `openapi.json` is out of date; regenerate it with `-Dsdp.openapi.update=true`.
- `./mvnw -pl services/device-api -am verify` also runs, on PostgreSQL 18 as a least-privilege `sdp_catalog` member:
  - `CatalogPrivilegesIT` (grants);
  - `DeviceApiIT`: every spec scenario, the 401/403 matrix, and a 1,000-device list with p95 < 500 ms;
  - `HistoryApiIT`: every telemetry-history scenario (latest, cursor paging over 1,200 rows, raw vs 6-hour buckets on 30 days of data, units, validation).

  See the root README for Podman settings.
