# Spec Delta

## ADDED Requirements

### Requirement: Anomalies page
When anomaly detection is enabled, the portal SHALL offer an Anomalies page, reachable from the navigation, listing recent anomalies of the analysed sites with filters on site and status (default: confirmed and to review), each linking to the device page over the anomaly's period; Editors SHALL be able to mark an anomaly as reviewed with a verdict and an optional comment.

#### Scenario: Review from the list
- **WHEN** an Editor reviews an anomaly to review with verdict "incorrect" and a comment
- **THEN** it leaves the default list and shows as reviewed with the verdict when the reviewed status is selected

#### Scenario: Feature disabled
- **WHEN** anomaly detection is disabled in the environment
- **THEN** the navigation has no Anomalies entry and the device page has no anomalies card
