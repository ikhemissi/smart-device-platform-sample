# provisioning-api

Platform API for site trust: which CA certificates allow devices of a site to join the platform through the Device Provisioning Service (DPS). It is published through API Management next to device-api, at `https://<apim>.azure-api.net/api/v1`, and implements the `site-certificates` spec (PRD-004, ADR-0006).

## Endpoints

| Method and path | Permission | Description |
|---|---|---|
| `POST /api/v1/sites` | `DeviceData.Write` | Creates a site (`siteId`: 3–40 lowercase letters, digits and single hyphens; `displayName` ≤ 100). Returns 201; 409 if the site exists. A new site trusts no certificate |
| `GET /api/v1/site-certificates` | `DeviceData.Read` | Certificates of every site (Sites list) |
| `GET /api/v1/sites/{siteId}/certificates` | `DeviceData.Read` | The site's certificates: slot (`primary`/`secondary`), subject, issuer, SHA-256 fingerprint, validity, `status` (`valid`, `expiring` within 30 days, `expired`), who added it and when |
| `POST /api/v1/sites/{siteId}/certificates` | `DeviceData.Write` | Adds a CA certificate, uploaded as the multipart part `file` (PEM or DER, ≤ 64 KB, exactly one certificate). 201 with the certificate and, when it re-enables devices, the device operation |
| `DELETE /api/v1/sites/{siteId}/certificates/{fingerprint}` | `DeviceData.Write` | Removes a certificate. Removing the last one disables the site's devices; the response holds the device operation |
| `GET /api/v1/sites/{siteId}/operations/{operationId}` | `DeviceData.Read` | Status of a device disable/re-enable operation (`PENDING`, `DONE`, `FAILED`) |
| `GET /api/v1/sites/{siteId}/audit` | `DeviceData.Read` | Trust changes, most recent first (`limit` default 50, maximum 200): action, certificate, actor, time |

- **Validation** (400 problem details): private keys are refused before parsing and never logged, as are non-CA certificates, certificates not yet valid or expired, and files with more or fewer than one certificate. A certificate the site already trusts → 409.
- **Slots**: a site trusts at most two certificates (`primary`, `secondary`) so a CA can be rotated without downtime: a third → 409. Removing the primary promotes the secondary.
- **Devices**: removing the last certificate sets every device of the site to `disabled` in IoT Hub (`devices.disabled_by_platform` remembers which); adding a certificate again re-enables only those. Sites with more than 100 devices are processed in the background: 202 with a `Location` header to the operation.
- **Errors**: DPS or IoT Hub unavailable → 503 with `Retry-After`; a concurrent change of the enrollment group → 409. Errors are RFC 9457 problem details.

The OpenAPI 3.0 document is [openapi.json](openapi.json). Terraform merges it with device-api's document into the single APIM API, and its operations carry a `set-backend-service` policy to this service (M4 design D6).

## How trust maps to Azure

- **Site ⇔ enrollment group** `site-<siteId>` (X.509 CA references in `primary`/`secondary`, initial twin tag `siteId`).
- **Certificate ⇔ verified DPS CA certificate** `site-<siteId>-<first 12 fingerprint characters>`. Bootstrap certificates of the demo sites are named `site-<siteId>`.
- **Order**: DPS is authoritative and changed first, with the enrollment group's ETag; the database is written after. Changes of a site are serialised by a PostgreSQL advisory lock.
- **Last certificate**: the enrollment group is disabled, not deleted, so `scripts/bootstrap-demo-sites.sh` (which skips existing groups) never restores trust a user removed.
- **Reconciliation** on start-up and every hour (one replica at a time): certificates found only in DPS are recorded (`added_by` `deployment` for bootstrap certificates, `reconciliation` otherwise), records missing from DPS are removed, and pending device operations are retried. Each repair is audited (`RECONCILED_*`).
- **Clients**: small REST clients with Entra tokens (no SAS keys): DPS enrollment groups (data plane), DPS certificates through Azure Resource Manager and the IoT Hub registry. The Azure IoT Java service SDKs only support shared access keys for DPS.

## Security

- **Tokens and roles**: same as device-api (shared auto-configuration in [`platform-common`](../../libs/platform-common)). The service checks tokens and `@RequiredRole` itself, since its Container Apps ingress is reachable directly.
- **Managed identity** `id-provisioning-api-*`:
  - DPS: *Device Provisioning Service Data Contributor* and the custom role *SDP DPS certificate manager (&lt;env&gt;)* (register, read and delete CA certificates only);
  - IoT Hub: *IoT Hub Registry Contributor*;
  - PostgreSQL: member of `sdp_provisioning`, which can read and insert sites (and update their display name), read device ids and sites, update only `devices.disabled_by_platform`, and use the trust tables (`site_audit_events` is insert-only).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `SDP_ENTRA_TENANT_ID`, `SDP_API_AUDIENCES` | — | As for device-api |
| `SDP_DB_URL`, `SDP_DB_USERNAME` | — | PostgreSQL JDBC URL and the principal name (Entra token authentication) |
| `SDP_DB_PASSWORDLESS` / `SDP_DB_PASSWORD` | `true` / — | Password authentication for local databases only |
| `SDP_DB_POOL_SIZE` | `6` | Connection pool size |
| `SDP_DPS_RESOURCE_ID` | — | Azure resource id of the DPS (CA certificates) |
| `SDP_DPS_HOST` | — | DPS service host, `<name>.azure-devices-provisioning.net` (enrollment groups) |
| `SDP_IOTHUB_HOST` | — | IoT Hub host name (device registry) |
| `SDP_RECONCILIATION_ENABLED` / `SDP_RECONCILIATION_INTERVAL` | `true` / `1h` | Start-up and periodic reconciliation |
| `SDP_SYNC_DEVICE_LIMIT` | `100` | Above this many devices, device operations run in the background (202) |
| `AZURE_CLIENT_ID` | — | Client id of the user-assigned managed identity |

## Running locally against the environment

Your identity needs DPS, IoT Hub and database access: `azd provision` grants the deployer DPS and IoT Hub data roles, and registering CA certificates uses your subscription role:

```bash
eval "$(azd env get-values)"
export SDP_DB_URL="jdbc:postgresql://$POSTGRES_HOST:5432/$POSTGRES_DATABASE?sslmode=require" \
       SDP_DB_USERNAME="$(az ad signed-in-user show --query userPrincipalName -o tsv)" \
       SDP_ENTRA_TENANT_ID="$ENTRA_TENANT_ID" SDP_API_AUDIENCES="$API_CLIENT_ID,api://$API_CLIENT_ID" \
       SDP_DPS_RESOURCE_ID="$(az iot dps show -n "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" --query id -o tsv)" \
       SDP_DPS_HOST="$DPS_SERVICE_HOST" SDP_IOTHUB_HOST="$IOTHUB_HOSTNAME"
./mvnw -pl services/provisioning-api -am spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
curl -H "Authorization: Bearer $(az account get-access-token --scope "$API_SCOPE" --query accessToken -o tsv)" \
     http://localhost:8081/api/v1/sites/paris-01/certificates
```

Tests: `CertificateValidatorTest` (generated certificates) and `SiteCertificatesIT` (PostgreSQL in Testcontainers, DPS and IoT Hub faked), one test per spec scenario.
