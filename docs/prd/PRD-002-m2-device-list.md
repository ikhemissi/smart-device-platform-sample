# PRD-002 — M2: Device list in the web portal

| Field | Value |
|---|---|
| Status | Draft |
| Milestone | M2 |
| Depends on | M1 |
| Overview | [PRD-000](PRD-000-product-overview.md) |

## 1. Goal

Give signed-in users a web portal where they can **find the devices** known to the platform, see their essential information at a glance, and — for Editors — **keep device information accurate**.

## 2. User stories

| ID | As a… | I want… | So that… |
|---|---|---|---|
| M2-US1 | Any user | to sign in with my organisational account | I do not need another password |
| M2-US2 | User without permission | to be told clearly that I have no access | I know whom to ask |
| M2-US3 | Data Viewer | to see the list of devices with name, site, format, last time seen and status | I get an overview of the fleet |
| M2-US4 | Data Viewer | to filter by site and search by name or identifier | I find a device quickly in a large fleet |
| M2-US5 | Data Viewer | to sort the list and browse it page by page | the list stays usable with many devices |
| M2-US6 | Data Viewer | to see whether a device is currently reporting or silent | I spot problems at a glance |
| M2-US7 | Data Editor | to edit a device's display name, description and location | the fleet information stays accurate |
| M2-US8 | Data Viewer | not to see editing actions | the portal is not confusing |

## 3. Acceptance criteria

1. Only signed-in users holding "Query device data" or "Edit device data" can see devices.
2. The device list shows: display name (or identifier when no name is set), site, format, first seen, last seen and status.
3. A device is shown as **silent** when it has not reported for more than **15 minutes**, otherwise **reporting**.
4. Filtering by site, searching by name/identifier, sorting and paging are available; the list displays in under **2 seconds** for 1,000 devices.
5. Editors can change display name, description and location (a device's site is determined by the certificate it joined with and cannot be edited); the change is visible immediately and records who changed it and when.
6. Viewers never see edit actions, and an attempt to edit without permission is refused.
7. The portal works in current versions of Edge, Chrome, Firefox and Safari on desktop.

## 4. Out of scope

- Measurements and charts (M3).
- Adding or deleting devices by hand (devices appear automatically when they first report).
- Managing who has which permission (done by an administrator outside the portal).
