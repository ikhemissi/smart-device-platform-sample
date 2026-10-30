# Spec Delta

## MODIFIED Requirements

### Requirement: Pipeline on every change of the main branch
A pipeline SHALL run on every push to the `main` branch, on every pull request targeting `main`, and on manual request, check every component of the repository (Java services and libraries, web portal, AI agent, infrastructure code, specifications), and report a single overall status on the commit or pull request. The run SHALL fail when any check fails and SHALL complete within 30 minutes.

#### Scenario: Push to main
- **WHEN** a commit is pushed to `main`
- **THEN** a pipeline run starts and its overall status (success or failure) is shown on the commit

#### Scenario: Pull request
- **WHEN** a pull request targeting `main` is opened or updated (for example by the GitHub Copilot cloud agent)
- **THEN** a pipeline run checks its head and its overall status is shown on the pull request

#### Scenario: Failing test
- **WHEN** a pushed commit breaks a unit or integration test of any component
- **THEN** the run fails and names the failing job and test
