# device-api

Platform API for the device catalog: the signed-in user's permissions, devices and sites. It is published through API Management at `https://<apim>.azure-api.net/api/v1` and implements the `device-catalog` and `user-access` specs of OpenSpec change `m2-device-catalog-portal` (ADR-0007).

## Endpoints

| Method and path | Permission | Description |
|---|---|---|
| `GET /api/v1/me` | any signed-in user | Display name, user principal name, object id, `canRead`, `canWrite` |
| `GET /api/v1/devices` | `DeviceData.Read` | Page of devices with `total`. Query: `siteId`, `status` (`reporting`\|`silent`), `search` (id or display name, case-insensitive), `sort` (`displayName`\|`siteId`\|`firstSeen`\|`lastSeen`), `order` (`asc`\|`desc`), `page` (1-based), `pageSize` (default 25, maximum 100) |
| `GET /api/v1/devices/{deviceId}` | `DeviceData.Read` | Device with description, location, last change (`updatedAt`, `updatedBy`) and an `ETag` header |
| `PATCH /api/v1/devices/{deviceId}` | `DeviceData.Write` | JSON Merge Patch (`application/merge-patch+json`) of `displayName` (≤ 100), `description` (≤ 1000), `location` (`latitude`, `longitude`, `label` ≤ 100). `If-Match` with the current ETag is required: missing → 428, outdated → 412. Other fields → 400 "is not editable" |
| `GET /api/v1/sites` | `DeviceData.Read` | Sites with their number of devices |

- A device is `reporting` when it was last seen within `SDP_REPORTING_WINDOW` (15 minutes), `silent` otherwise.
- The ETag covers only the editable fields and the last change, so incoming telemetry does not invalidate an edit in progress.
- Errors are RFC 9457 problem details (`application/problem+json`); validation errors list the messages per field in `errors`.

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
  - `UPDATE` only on the editable device columns and the audit columns.

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
| `AZURE_CLIENT_ID` | — | Client id of the user-assigned managed identity |

## Running locally against the environment's database

The service connects with your own Entra identity, which needs network access to the server; the developer firewall rule is created by `azd provision`:

```bash
eval "$(azd env get-values)"
export SDP_DB_URL="jdbc:postgresql://$POSTGRES_HOST:5432/$POSTGRES_DATABASE?sslmode=require" \
       SDP_DB_USERNAME="$(az ad signed-in-user show --query userPrincipalName -o tsv)" \
       SDP_ENTRA_TENANT_ID="$ENTRA_TENANT_ID" SDP_API_AUDIENCES="$API_CLIENT_ID,api://$API_CLIENT_ID"
./mvnw -pl services/device-api -am spring-boot:run
curl -H "Authorization: Bearer $(az account get-access-token --scope "$API_SCOPE" --query accessToken -o tsv)" \
     http://localhost:8080/api/v1/devices?pageSize=5
```

## Tests

- `./mvnw -pl services/device-api test`:
  - merge-patch rules and ETag behaviour;
  - the OpenAPI document. `OpenApiDocumentTest` fails when `openapi.json` is out of date; regenerate it with `-Dsdp.openapi.update=true`.
- `./mvnw -pl services/device-api -am verify` also runs, on PostgreSQL 18 as a least-privilege `sdp_catalog` member:
  - `CatalogPrivilegesIT` (grants);
  - `DeviceApiIT`: every spec scenario, the 401/403 matrix, and a 1,000-device list with p95 < 500 ms.

  See the root README for Podman settings.
