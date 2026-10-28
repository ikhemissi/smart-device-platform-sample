# threshold-alerting Specification

## Purpose

Notifies the right people by email when a monitored device's reading leaves the range defined by an Editor, avoids notification floods, and keeps an auditable history of alerts.

## Requirements

### Requirement: Measurement publication for alerting
Every persisted measurement SHALL be published, after persistence and before its batch is acknowledged, as a message carrying device id, site id, payload format, measurement time and metrics, with device id, site id and payload format also exposed as filterable message properties and a message id unique across devices (derived from device id and measurement message key).

#### Scenario: Published after persistence
- **WHEN** a batch of 50 measurements is persisted
- **THEN** 50 messages are published, each with a distinct message id derived from its device id and message key, and properties `deviceId`, `siteId`, `payloadFormat`

#### Scenario: Same message key on two devices
- **WHEN** two different devices send measurements with the same device message id
- **THEN** both measurements are published and evaluated

#### Scenario: Publication failure
- **WHEN** publication fails for a batch
- **THEN** the batch is not acknowledged and is retried; no duplicate measurement is stored

### Requirement: Monitored device subset
Only measurements matching the configured monitoring filter (expressed on `siteId` and/or `deviceId`) SHALL be evaluated for alerts. The filter SHALL be changeable by the platform operator through deployment configuration without code change.

#### Scenario: Unmonitored device never alerts
- **WHEN** the filter is `siteId = 'paris-01'` and a `lyon-01` device breaches one of its thresholds
- **THEN** no alert is opened and no email is sent

#### Scenario: Filter change
- **WHEN** the operator changes the filter to include `lyon-01` and redeploys
- **THEN** subsequent `lyon-01` breaches open alerts

### Requirement: Threshold management
The API SHALL let Readers list a device's thresholds (`GET /api/v1/devices/{id}/thresholds`) and Editors create, update, enable/disable and delete them. A threshold has a metric, an optional minimum and/or maximum (at least one; min < max), 1–10 recipient email addresses and an enabled flag; at most 20 thresholds per device.

#### Scenario: Create a cold-room threshold
- **WHEN** an Editor creates a threshold on `temperature` with min 2 and max 8 for two recipients
- **THEN** the response is 201 and the threshold is listed for the device

#### Scenario: Invalid range
- **WHEN** an Editor submits min 8 and max 2
- **THEN** the response is 400 stating min must be lower than max

### Requirement: Alert opening
For a monitored device and an enabled threshold, a measurement whose metric value is below the minimum or above the maximum SHALL open an alert (if none is open for that threshold) and send an alert email to the recipients within 2 minutes of the measurement being received.

#### Scenario: Temperature above maximum
- **WHEN** a monitored device with threshold max 8 °C reports 12 °C
- **THEN** an alert is opened and recipients receive an email within 2 minutes

### Requirement: No flooding and reminders
While an alert is open, further breaching measurements SHALL NOT send emails except one reminder per hour of continuous breach. Duplicate deliveries of the same measurement SHALL NOT produce additional emails or state changes, and measurements older than the last one evaluated for that threshold SHALL be ignored for state changes.

#### Scenario: Continuous breach for 30 minutes
- **WHEN** a device keeps reporting above the maximum for 30 minutes
- **THEN** exactly one alert email is sent

#### Scenario: Continuous breach for 70 minutes
- **WHEN** a device keeps reporting above the maximum for 70 minutes
- **THEN** one alert email and one reminder email are sent

### Requirement: Alert resolution
When a measurement of an open alert's metric is back within the threshold range, the alert SHALL be closed and a single "back to normal" email SHALL be sent. Disabling or deleting a threshold SHALL close its open alert without email.

#### Scenario: Back to normal
- **WHEN** a device with an open alert reports a value within range
- **THEN** the alert is closed with its end time and the recipients receive one resolution email

### Requirement: Email content
Alert, reminder and resolution emails SHALL be sent from the platform's sender address and state the device (display name and id), site, metric with unit, current value, worst value, threshold, times, and a link to the device page.

#### Scenario: Alert email content
- **WHEN** an alert email is sent for `temperature` 12 °C above max 8 °C
- **THEN** the subject contains the device name, metric and "above 8 °C", and the body links to the device page

### Requirement: Notification reliability
Email sending failures SHALL be retried; after final failure the failure SHALL be recorded on the alert without blocking alert state changes.

#### Scenario: Email service outage
- **WHEN** the email service is unavailable when an alert opens
- **THEN** the alert is opened, retries occur, and if all fail the alert shows a notification failure

### Requirement: Alert history
The API SHALL expose `GET /api/v1/devices/{id}/alerts?from=&to=` and `GET /api/v1/alerts?status=&siteId=&from=&to=` (Read) returning alerts with threshold snapshot, metric, opened/closed times, worst and last values, and notifications sent.

#### Scenario: Open alerts of a site
- **WHEN** a Reader requests `GET /api/v1/alerts?status=open&siteId=paris-01`
- **THEN** only currently open alerts of `paris-01` devices are returned

### Requirement: Alerting in the portal
The device page SHALL show the device's thresholds to all users with create/edit/enable/delete actions for Editors only, and an alert history list (most recent first, open alerts highlighted).

#### Scenario: Reader view
- **WHEN** a Reader opens a device page with thresholds and past alerts
- **THEN** thresholds and alert history are visible and no threshold edit action is displayed
