# Design: Continuous integration

## Context

See proposal.md for the motivation and `specs/continuous-integration/spec.md` for the required behaviour. Current state:

- Checks exist but only run locally: Maven wrapper with Failsafe integration tests on Testcontainers (Podman locally; PostgreSQL, Event Hubs and Service Bus emulators); portal `oxlint`, `tsc`, Vitest and offline Playwright in the official Playwright image (`scripts/e2e-podman.sh`); agent Ruff and pytest with uv; Terraform 1.9+ with providers pinned by `.terraform.lock.hcl`.
- No Java formatter or linter is configured; adding one would reformat the whole code base.
- The repository is public on GitHub (free runners, code scanning available).
- Security conventions: passwordless, no secrets anywhere ([ADR-0005](../../../docs/adr/ADR-0005-passwordless-identity.md)); testing conventions in the [implementation guidelines](../../../docs/architecture/implementation-guidelines.md).

## Goals / Non-Goals

**Goals:** reuse the commands developers already run locally, so a red job reproduces with one command; keep the pipeline under 30 minutes with parallel jobs and caches; no credential of any kind.

**Non-Goals:** deployment or `terraform plan` against Azure (needs an identity; chosen out of scope); environment tests against `sdp-dev`; pull-request workflows (the repository works on `main`); adding a Java formatter; building the Java service images (Azure Container Registry builds them at deploy time).

## Decisions

### D1. One workflow, parallel jobs
`.github/workflows/ci.yml`, triggers `push` on `main` and `workflow_dispatch`; `concurrency` per ref with cancel-in-progress. Jobs (all on `ubuntu-latest`, independent, so one failure shows all others' results):

| Job | Steps |
|---|---|
| `java` | `setup-java` (Microsoft OpenJDK 25, Maven cache) → `./mvnw -B verify` (unit + integration tests on the runner's Docker) → test reports uploaded on failure |
| `portal` | `setup-node` (Node 24 LTS, npm cache) → `npm ci`, `npm run lint`, `npx tsc -b`, `npm test`, `npm run build` → Playwright tests in `mcr.microsoft.com/playwright:v<version>-noble` (version read from `package-lock.json`, as `e2e-podman.sh` does) |
| `agent` | `setup-uv` → `uv sync --locked`, `ruff check`, `ruff format --check`, `pytest` → `docker build` of the agent image |
| `infra-specs` | `setup-terraform` → `fmt -check -recursive`, `init -backend=false`, `validate`; `actionlint`; `npx @fission-ai/openspec validate --all --strict` |
| `dependencies` | vulnerability scan (D2) → SARIF upload; outdated report (D3) |

Alternative: one workflow per component with path filters — rejected: a single status per commit is simpler to read, and the whole run fits the time budget.

### D2. Vulnerability scanning with Trivy
Trivy scans the repository (`pom.xml` with transitive resolution, `package-lock.json`, `uv.lock`), the Java services' runtime base image (the services only add jars, already covered) and the AI agent's image as built from its Dockerfile (base-image findings in unused tools, such as the libraries vendored by `pip`, are removed at build time rather than accepted), in two passes, through `scripts/scan-vulnerabilities.sh` (same command locally):

1. **Gate**: severities HIGH and CRITICAL, `--ignore-unfixed`, exit code 1, honouring `.trivyignore.yaml` (each entry: id, path, statement, `expired_at`).
2. **Report**: all severities and unfixed findings to SARIF, uploaded to code scanning (`github/codeql-action/upload-sarif`), even when the gate failed.

Alternatives: OWASP Dependency-Check — needs an NVD API key (a secret) and is slow; OSV-Scanner — no built-in severity threshold; GitHub Dependabot alerts — not a pipeline status. Trivy also covers base images with the same ignore file.

### D3. Outdated report, never failing
`scripts/report-outdated.sh` writes Markdown tables to `$GITHUB_STEP_SUMMARY` (stdout locally): Maven (`versions-maven-plugin display-dependency-updates`/`display-plugin-updates`, no POM change), npm (`npm outdated --json`), Python (`uv tree --outdated --depth 1`), Terraform providers (lock file versus registry API), actions (pinned versions versus latest releases, via the `GITHUB_TOKEN`). Each section runs with `|| true` semantics so a lookup failure only shows a note.

Alternative: Dependabot version updates — opens pull requests rather than reporting; can be added later on top.

### D4. Least privilege and supply chain
Workflow-level `permissions: contents: read`; the `dependencies` job adds `security-events: write`. No `secrets.*` references. Every action is pinned to a full commit SHA with the version in a comment (`uses: actions/checkout@<sha> # v7.0.1`); the outdated report flags newer releases. The guidelines record these conventions (reversible, so not an ADR).

### D5. Caches and duration
Maven (`~/.m2/repository`), npm and uv caches keyed on their lock files; Trivy database cached per day. Expected durations: java ≈ 12–15 min (emulator images dominate), others ≤ 8 min, in parallel.

## Risks / Trade-offs

- [Emulator images are large and the Service Bus emulator needs an x86 SQL Edge container] → GitHub's x86 runners support them; Failsafe already uses one container start per JVM where needed.
- [Pixel snapshots of Playwright differ between environments] → the same Playwright image as locally, so snapshots stay valid.
- [A new CVE turns `main` red without a code change] → intended (fail-high policy); fix by upgrading, or accept in `.trivyignore.yaml` with a justification and an expiry. Decided 2026-10-29: CVE-2026-111943 (Go standard library in the `jaz` launcher of `mcr.microsoft.com/openjdk/jdk:25-distroless`, not executed by the services) is **not** accepted; the dependency job stays red until Microsoft republishes the image.
- [Trivy resolves Maven dependencies from Maven Central at scan time] → network access is available on runners; scan failures fail the gate visibly rather than silently passing.
- [Pinned SHAs age] → listed by the outdated report.

## Migration Plan

Additive: commit the workflow, push to `main`, check the first run. Rollback: delete or disable the workflow.
