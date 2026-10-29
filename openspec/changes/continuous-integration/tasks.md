# Tasks: Continuous integration

## 1. Build, quality and test jobs

- [x] 1.1 Create `.github/workflows/ci.yml` (push to `main` and manual trigger, concurrency, `contents: read`, actions pinned to commit SHAs) with the `java`, `portal`, `agent` and `infra-specs` jobs of design D1; verify `actionlint` reports no error and no `secrets.` reference exists
- [x] 1.2 Run every command of these jobs locally as the workflow runs them and fix what fails (formatting, validation); verify each command exits 0
- [x] 1.3 Document the pipeline in the README (diagram, jobs, how to reproduce each job locally, status badge), in AGENTS.md and in the implementation guidelines (CI conventions: least privilege, SHA pinning); verify the README links and commands

## 2. Dependency health

- [x] 2.1 Add the `dependencies` job: Trivy gate (HIGH/CRITICAL with a fix, `.trivyignore.yaml` with justification and expiry) over Maven, npm, uv and the Dockerfiles' base images, and a full SARIF report uploaded to code scanning (`security-events: write` for this job only); verify by running the same Trivy commands locally and resolving or (with the user's agreement) accepting every gating finding (2026-10-29: Python base-image findings resolved by removing pip from the agent image; CVE-2026-111943 in Microsoft's JDK runtime image kept failing on purpose, by the user's decision, until a fixed image is published)
- [x] 2.2 Add `scripts/report-outdated.sh` (Maven, npm, Python, Terraform providers, pinned actions) writing Markdown to the run summary and never failing; verify it runs locally, prints the tables and exits 0 even when a lookup fails
- [x] 2.3 Document the vulnerability policy (gate, acceptance with expiry, where to see findings) and the outdated report in the README; verify against the workflow

## 3. First runs on GitHub

- [ ] 3.1 Push to `main` and verify the run: every job green, total duration under 30 minutes, findings visible in code scanning, outdated report in the run summary
- [x] 3.2 On a throwaway branch with a deliberately failing unit test, start a manual run (`workflow_dispatch`); verify it runs the same jobs, fails and names the failing test (branch deleted afterwards)

## Workflow follow-up

- Archive the change after review so `continuous-integration` becomes a baseline spec.
