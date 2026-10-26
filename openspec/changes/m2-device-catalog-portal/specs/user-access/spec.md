# Spec Delta

## Purpose

Defines how people sign in, which permission levels exist, and how every platform API enforces them, so that data is only visible to authorised users and every change is attributable.

## ADDED Requirements

### Requirement: Organisational sign-in
Users SHALL sign in with their Microsoft Entra ID organisational account; the platform SHALL NOT manage its own passwords.

#### Scenario: Unauthenticated visit
- **WHEN** an unauthenticated user opens the portal
- **THEN** they are redirected to Microsoft Entra ID sign-in and, after success, return to the page they requested

### Requirement: Two permission levels
The platform SHALL define two app roles: `DeviceData.Read` (query devices, measurements, sites, alerts, use the AI assistant) and `DeviceData.Write` (everything in Read, plus editing device information, sites, site certificates and alert thresholds). Holding `DeviceData.Write` SHALL imply Read permissions.

#### Scenario: Writer can read
- **WHEN** a user holding only `DeviceData.Write` lists devices
- **THEN** the request succeeds

### Requirement: Enforcement on every API call
Every API operation SHALL require a valid Entra access token for the platform API audience and SHALL declare its required permission: operations that change platform data require Write; read operations (and operations on the user's own assistant conversations) require Read or Write. Missing/invalid/expired tokens SHALL yield HTTP 401 and insufficient role HTTP 403, enforced both at the gateway and in the backend.

#### Scenario: No token
- **WHEN** a client calls `GET /api/v1/devices` without a token
- **THEN** the response is 401

#### Scenario: Reader attempts an edit
- **WHEN** a user holding only `DeviceData.Read` calls `PATCH /api/v1/devices/{id}`
- **THEN** the response is 403 and nothing is changed

#### Scenario: Gateway bypass
- **WHEN** a client calls a backend service directly, bypassing the gateway, without a valid token
- **THEN** the backend responds 401

### Requirement: Users without permission
A signed-in user holding neither role SHALL be denied all data and shown an explanation naming the permission to request.

#### Scenario: No role
- **WHEN** a signed-in user without any platform role opens the portal
- **THEN** an access-denied page explains that the "Query device data" permission must be granted by an administrator, and no data request succeeds

### Requirement: Current user information
The API SHALL expose `GET /api/v1/me` returning the user's display name, user principal name, object id and effective permissions (`canRead`, `canWrite`).

#### Scenario: Editor information
- **WHEN** a user holding `DeviceData.Write` calls `GET /api/v1/me`
- **THEN** the response contains `canRead: true` and `canWrite: true`

### Requirement: Attributable changes
Every mutating operation SHALL record the acting user's object id and display name and the UTC time of the change.

#### Scenario: Edit is attributed
- **WHEN** an Editor changes a device's display name
- **THEN** the device's `updatedBy` and `updatedAt` reflect that Editor and the time of the change
