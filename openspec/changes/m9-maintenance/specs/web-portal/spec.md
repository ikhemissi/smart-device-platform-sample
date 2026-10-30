# Spec Delta

## ADDED Requirements

### Requirement: Maintenance page
When maintenance is enabled, the portal SHALL offer a Maintenance page, reachable from the navigation, listing the queue most likely first with filters on site and status (default: open and planned): device (linking to its page), site, probability, days left, reasons, work order and status; Editors SHALL be able to mark an item planned or done with an optional comment.

#### Scenario: Plan then close a visit
- **WHEN** an Editor marks an open item planned, then done with a comment
- **THEN** it shows as planned, then leaves the default list and shows as done with the comment when the done status is selected
