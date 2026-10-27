# Spec Delta

## Purpose

Lets clients find, read and maintain devices known to the platform and the sites they belong to, through a stable HTTP API.

## ADDED Requirements

### Requirement: List devices
`GET /api/v1/devices` SHALL return a page of devices with id, display name, site id, last payload format, first-seen, last-seen, status, and the total count. It SHALL support `siteId` and `status` (`reporting`|`silent`) filters, case-insensitive `search` on id and display name, `sort` on `displayName|siteId|firstSeen|lastSeen` with direction, and `page`/`pageSize` (default 25, maximum 100).

#### Scenario: Filter and search
- **WHEN** a Reader requests devices with `siteId=lyon-01&search=ruuvi`
- **THEN** only devices of site `lyon-01` whose id or display name contains "ruuvi" (any case) are returned, with the matching total count

#### Scenario: Page size limit
- **WHEN** a client requests `pageSize=500`
- **THEN** the response is 400 with a problem description stating the maximum of 100

### Requirement: Device status
A device's status SHALL be `reporting` when its last-seen time is within the last 15 minutes and `silent` otherwise, evaluated at request time.

#### Scenario: Silent device
- **WHEN** a device was last seen 20 minutes ago
- **THEN** its status is `silent`

### Requirement: Get a device
`GET /api/v1/devices/{deviceId}` SHALL return the device with all list fields plus description, location (latitude, longitude, label), `updatedBy`, `updatedAt` and an entity tag; an unknown id SHALL return 404.

#### Scenario: Unknown device
- **WHEN** a client requests a device id that does not exist
- **THEN** the response is 404 in problem format

### Requirement: Edit device information
`PATCH /api/v1/devices/{deviceId}` SHALL let Editors change display name (≤ 100 chars), description (≤ 1000 chars) and location (latitude −90..90, longitude −180..180, label ≤ 100 chars), requiring `If-Match` with the current entity tag. Device id, site and ingestion fields SHALL NOT be editable.

#### Scenario: Successful edit
- **WHEN** an Editor patches the display name with the current entity tag
- **THEN** the response is 200 with the updated device and a new entity tag

#### Scenario: Concurrent edit
- **WHEN** an Editor patches with an outdated entity tag
- **THEN** the response is 412 and nothing is changed

#### Scenario: Attempt to change site
- **WHEN** a patch contains `siteId`
- **THEN** the response is 400 stating the field is not editable

### Requirement: List sites
`GET /api/v1/sites` SHALL return every site with id, display name and number of devices.

#### Scenario: Sites for filtering
- **WHEN** a Reader lists sites after the demo bootstrap
- **THEN** `paris-01`, `lyon-01` and `marseille-01` are returned with their device counts

### Requirement: Error format and performance
API errors SHALL use RFC 9457 problem details. Device list requests for a fleet of 1,000 devices SHALL complete within 500 ms at the 95th percentile, measured at the gateway.

#### Scenario: Validation error format
- **WHEN** a request fails validation
- **THEN** the response has content type `application/problem+json` with `title`, `status` and per-field `errors`
