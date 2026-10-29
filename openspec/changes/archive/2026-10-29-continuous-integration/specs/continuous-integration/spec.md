# Spec Delta

## Purpose

Checks every change of the main branch automatically — build, code quality, tests, infrastructure validation and third-party dependency health — without any credential, so regressions and vulnerable dependencies are detected as soon as they land.

## ADDED Requirements

### Requirement: Pipeline on every change of the main branch
A pipeline SHALL run on every push to the `main` branch and on manual request, check every component of the repository (Java services and libraries, web portal, AI agent, infrastructure code, specifications), and report a single overall status on the commit. The run SHALL fail when any check fails and SHALL complete within 30 minutes.

#### Scenario: Push to main
- **WHEN** a commit is pushed to `main`
- **THEN** a pipeline run starts and its overall status (success or failure) is shown on the commit

#### Scenario: Failing test
- **WHEN** a pushed commit breaks a unit or integration test of any component
- **THEN** the run fails and names the failing job and test

### Requirement: Build, code quality and tests
For each component, the pipeline SHALL build it, run its static checks (lint, format and type checks where the component defines them), and run its unit tests and its integration or end-to-end tests that need no Azure access (container-based emulators and a faked API are allowed).

#### Scenario: Lint violation
- **WHEN** a commit introduces a lint or format violation in the portal or the AI agent
- **THEN** the run fails

#### Scenario: Integration tests with emulators
- **WHEN** the pipeline runs the Java integration tests
- **THEN** they run against containerised PostgreSQL, Event Hubs and Service Bus emulators, without Azure

### Requirement: Infrastructure and specification validation
The pipeline SHALL check the infrastructure code's formatting and validity, the pipeline definition itself, and the OpenSpec specifications and changes, without contacting Azure.

#### Scenario: Invalid Terraform
- **WHEN** a commit makes the Terraform configuration invalid or unformatted
- **THEN** the run fails without any Azure call

### Requirement: Dependency vulnerability gate
The pipeline SHALL scan the third-party dependencies of every component (Java, npm, Python, container base images) for published vulnerabilities, SHALL fail when a finding of severity High or Critical for which a fixed version exists is not explicitly accepted, and SHALL publish all findings, including those without a fix, to the repository's code scanning view. An accepted finding SHALL be recorded in the repository with a justification and an expiry date, after which it fails the run again.

#### Scenario: Vulnerable dependency
- **WHEN** a dependency with a known Critical vulnerability is added
- **THEN** the run fails and the finding is listed with its identifier and fixed version

#### Scenario: Accepted finding
- **WHEN** a High finding has a recorded acceptance that has not expired
- **THEN** the run does not fail on it, and fails again once the acceptance expires

#### Scenario: No fix available
- **WHEN** a High finding has no fixed version yet
- **THEN** the run does not fail on it and the finding is visible in code scanning

#### Scenario: Lower severities
- **WHEN** only Low or Medium findings exist
- **THEN** the run does not fail and the findings are visible in code scanning

### Requirement: Outdated dependency report
The pipeline SHALL report, in the run summary, the direct dependencies of every ecosystem (Java, npm, Python, Terraform providers, pipeline actions) for which a newer version exists, with the current and latest versions. The report SHALL never fail the run.

#### Scenario: Newer version available
- **WHEN** a newer version of a direct dependency is published
- **THEN** the next run's summary lists it with current and latest versions, and the run status is unaffected

### Requirement: No credentials and least privilege
The pipeline SHALL use no stored secret and no cloud credential, SHALL grant its token only read access to the repository (plus writing code scanning results), and SHALL reference third-party actions by immutable versions.

#### Scenario: No secrets
- **WHEN** the pipeline definition and repository settings are reviewed
- **THEN** no secret, key or cloud identity is used, and every third-party action is pinned to a commit

### Requirement: Reproducible locally
Each pipeline check SHALL be reproducible on a developer machine with documented commands.

#### Scenario: Reproduce a failure
- **WHEN** a developer reads the documentation of a failed job
- **THEN** they find the command that runs the same check locally
