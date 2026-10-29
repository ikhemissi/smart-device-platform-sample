# Spec Delta

## ADDED Requirements

### Requirement: Device anomalies
When anomaly detection is enabled, the device page SHALL show the device's anomalies of the selected period, most recent first: metric, period, observed versus usual values, classification with its confidence, status, explanation, and review; anomalies to review SHALL be visibly distinguished from confirmed ones, and dismissed ones hidden unless requested.

#### Scenario: Confirmed anomaly on a device
- **WHEN** a user opens the page of a device with a confirmed real event in the selected period
- **THEN** the anomaly is listed with "Real event · 91 %", the period, the values and its explanation

#### Scenario: Device of a site that is not analysed
- **WHEN** the device's site is not analysed
- **THEN** the card says that anomalies are not analysed for this site
