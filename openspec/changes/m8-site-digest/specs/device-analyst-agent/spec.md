# Spec Delta

## ADDED Requirements

### Requirement: Site digest questions
When site digests are enabled, the assistant SHALL be able to return a site's latest digest through the platform API with the user's permissions, presenting its period, brief and figures as recorded and citing the site.

#### Scenario: Yesterday at a site
- **WHEN** a user asks "What happened at lyon-01 in the last day?"
- **THEN** the answer uses the site's latest digest, states its period and generation time, and cites the site
