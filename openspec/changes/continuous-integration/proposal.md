# Proposal: Continuous integration

## Why

The demonstrator must stay deployable with a single command and free of secrets ([PRD-000 §7](../../../docs/prd/PRD-000-product-overview.md)). Today every check (builds, linters, about 200 tests across Java, the portal and the agent, Terraform validation) only runs on a developer machine, and nothing watches the 1,000+ third-party dependencies for published vulnerabilities. A GitHub Actions pipeline on every push to `main` catches regressions and vulnerable dependencies as soon as they land, and shows the customer how the sample is kept healthy.

## What Changes

- A GitHub Actions workflow, triggered on every push to `main` (and manually), with parallel jobs:
  - **Java**: build and unit tests, then integration tests with Testcontainers (PostgreSQL, Event Hubs and Service Bus emulators) on the runner's Docker.
  - **Portal**: lint, type check, unit tests, production build, and the offline Playwright end-to-end tests in the official Playwright image.
  - **AI agent**: Ruff lint and format check, unit tests, image build check.
  - **Infrastructure and specs**: Terraform format and validation (no backend, no Azure access), workflow linting, OpenSpec strict validation.
  - **Dependencies**: a vulnerability scan of Maven, npm, Python (uv) and container base images that **fails on High or Critical** findings, with results published to GitHub code scanning; a report of **outdated dependencies** (Maven, npm, Python, Terraform providers, actions) in the run summary, which never fails the run.
- **No Azure access and no secrets**: the workflow uses only the built-in `GITHUB_TOKEN` with least-privilege permissions; third-party actions are pinned to commit SHAs.
- README, AGENTS.md and the implementation guidelines describe the pipeline and how to reproduce each job locally.

## Capabilities

### New Capabilities
- `continuous-integration`: automated checks on every change of the main branch — build, lint, tests, infrastructure validation, dependency vulnerability gate and outdated-dependency report — without credentials.

### Modified Capabilities
<!-- None: no runtime behaviour of the platform changes. -->

## Impact

- New `.github/workflows/ci.yml` (and a reusable outdated-dependency report script under `scripts/`).
- No change to services, infrastructure or the Azure environment; no new Azure identity.
- Pushes to `main` take about 15–20 minutes of runner time (public repository: free).
- A vulnerable dependency published after a merge turns the next run red until it is upgraded or explicitly accepted (documented ignore file with an expiry).
