# PRD-005 — M5: Threshold alerting

| Field | Value |
|---|---|
| Status | Draft |
| Milestone | M5 |
| Depends on | M3 |
| Overview | [PRD-000](PRD-000-product-overview.md) |

## 1. Goal

Notify the right people **by email** when a reading of a **monitored device** goes outside its expected range, without flooding them, and keep a history of these alerts.

## 2. Business context

Not every device is critical: only a **subset of devices** (for instance all devices of a site, or a hand-picked list) is monitored. For monitored devices, Editors define the acceptable range of a reading (e.g. temperature between 2 °C and 8 °C for a cold room).

## 3. User stories

| ID | As a… | I want… | So that… |
|---|---|---|---|
| M5-US1 | Data Editor | to define, for a device and a reading, a minimum and/or maximum value and the email recipients | the right people are told when it goes wrong |
| M5-US2 | Data Editor | to enable/disable a threshold without deleting it | I can silence it during maintenance |
| M5-US3 | Alert recipient | to receive a clear email stating device, site, reading, value, threshold and time | I can act without opening the portal |
| M5-US4 | Alert recipient | not to receive one email per measurement while the device stays out of range | I am not flooded |
| M5-US5 | Alert recipient | to be told when the reading is back to normal | I know the situation is resolved |
| M5-US6 | Data Viewer | to see a device's alert history in the portal | I understand past incidents |
| M5-US7 | Platform operator | to choose which devices are monitored (by site or by device) | only critical devices generate alerts |

## 4. Acceptance criteria

1. When a monitored device reports a value outside an enabled threshold, an email is sent within **2 minutes**.
2. While the device stays out of range, no further email is sent for that threshold; a reminder may be sent at most once per hour.
3. When the reading returns within range, a single "back to normal" email is sent.
4. Devices that are not monitored never trigger alerts, even if thresholds are defined for them.
5. The device page shows thresholds (everyone) and lets Editors create, change, enable/disable and delete them.
6. The device page shows the alert history: start time, end time, reading, worst value, threshold.
7. Emails are sent from a platform address and contain a link to the device page.

## 5. Out of scope

- Other notification channels (SMS, Teams, push).
- Alerts on missing data (device silent).
- Rules combining several readings or devices.
- Managing the monitored subset from the portal (done by the platform operator through configuration for the demo).
