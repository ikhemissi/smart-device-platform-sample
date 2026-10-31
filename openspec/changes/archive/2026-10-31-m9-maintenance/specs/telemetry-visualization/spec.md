# Spec Delta

## ADDED Requirements

### Requirement: Device battery forecast
When maintenance is enabled, the device page SHALL show the device's latest assessment: battery level, trend per day and estimated days left when the device reports a battery, the intervention probability, the assessment time, and its queue item (status, work order) if any.

#### Scenario: Aging device page
- **WHEN** a user opens the page of a device whose battery has about 3 days left
- **THEN** the page shows the battery level, the daily trend, "about 3 days left", the probability and the open queue item
