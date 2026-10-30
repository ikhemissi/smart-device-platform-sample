# Spec Delta

## MODIFIED Requirements

### Requirement: Figures
Each digest SHALL hold figures computed from the platform's data, never by a model, covering the site's devices (reporting and silent), each reported metric (average over the period and over the comparison period, and the change), alerts, anomalies, rejected messages, site certificates expiring within 30 days and, when maintenance is enabled, the site's maintenance queue.

#### Scenario: Silent device
- **WHEN** a device of the site has not reported for more than 15 minutes at generation time
- **THEN** the figures list it as silent with its last report time

#### Scenario: Alerts and anomalies
- **WHEN** alerts opened and anomalies were recorded at the site during the period
- **THEN** the figures list each alert (device, metric, status) and the anomalies by status (device, metric, classification, confidence)

#### Scenario: Maintenance queue
- **WHEN** devices of the site are in the maintenance queue at generation time
- **THEN** the figures give the number of items by status and the most urgent devices with their probability and days left
