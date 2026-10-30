# Spec Delta

## ADDED Requirements

### Requirement: Maintenance questions
When maintenance is enabled, the assistant SHALL be able to list the maintenance queue by site and status through the platform API with the user's permissions, presenting each device's probability, days left and reasons as recorded and citing the devices.

#### Scenario: Visits this week
- **WHEN** a user asks "Which devices need a visit this week?"
- **THEN** the answer lists the queued devices most likely first, with probabilities, days left and reasons, or says the queue is empty
