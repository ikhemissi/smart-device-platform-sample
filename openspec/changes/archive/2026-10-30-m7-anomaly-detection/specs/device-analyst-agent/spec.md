# Spec Delta

## ADDED Requirements

### Requirement: Anomaly questions
When anomaly detection is enabled, the assistant SHALL be able to list recent anomalies by site, device, status and period through the platform API with the user's permissions, and SHALL present their classification and confidence as the platform recorded them, citing the devices and periods.

#### Scenario: Anomalies at a site
- **WHEN** a user asks "Anything unusual at paris-01 today?"
- **THEN** the answer lists today's confirmed anomalies and the ones to review, with classifications, confidences, devices and periods, or says there were none
