# AGENTS.md — directives for coding agents

How to work on this repository. These rules come from how the demonstrator was built; follow them unless the user says otherwise in the current request. Product and architecture facts live in the documents linked below — read them instead of guessing.

## Where things are

| Topic | Source of truth |
|---|---|
| Business requirements (no technical choices) | `docs/prd/` |
| Architecture decisions that are **hard to reverse** | `docs/adr/` |
| Reversible, cross-cutting conventions (stack, naming, security, observability, testing) | `docs/architecture/implementation-guidelines.md` |
| Specifications (baseline) | `openspec/specs/` |
| Work in progress: proposal, design, specs delta, tasks | `openspec/changes/<change>/` |
| Demo guides (one per milestone) | `docs/demo/` |

## Workflow

- **Spec-driven with OpenSpec.** Every functional change is an OpenSpec change: propose → apply (task by task) → archive (which syncs the delta specs into `openspec/specs/`). Use the skills in `.github/skills/` (`openspec-propose`, `openspec-apply-change`, `openspec-archive-change`, …). Documentation-only work does not need a change.
- **Ask before deciding** when a choice changes the design, the scope or the behaviour; propose a default.
- **ADRs only for hard-to-reverse decisions.** Easily reversible choices go to the implementation guidelines. If a design would contradict an ADR, propose a new ADR instead of diverging silently.
- **Keep documents in sync** with the code in the same commit: the design of the change, the implementation guidelines, the README (status, milestone table, settings), the service READMEs and the demo guides.
- **Verify every task** before ticking it, with the check the task names (tests, a measurement, a query against the environment). Ask the user for manual checks that need the portal (interactive Entra sign-in).
- **Commits:** conventional commits (`feat(scope):`, `fix:`, `docs(openspec):`, `test:` …), one per coherent step, ending with the trailer `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`. Do not push unless asked.

## Azure environment

- **One long-lived environment, `sdp-dev`** (azd), updated incrementally — never create or tear down environments unless explicitly asked. It also holds the telemetry shown to the customer.
- **Always preview before applying:** `azd provision --preview`, read the plan (only expected adds/changes, no unexpected destroy), then `azd provision`. Deploy services with `azd deploy <service>`.
- **Terraform owns the infrastructure** (`infra/`, deployed by azd). Manual changes (e.g. container app environment variables) are reverted by the next provision; make them in Terraform.
- azd does not pass `TF_VAR_*` to Terraform: expose settings as azd environment values mapped in `infra/main.tfvars.json` (empty value → default through `coalesce`).
- Region `francecentral` for everything except Static Web Apps (`eastus2`). Every resource carries the tags `SecurityControl=Ignore` and `CostControl=Ignore`.
- API Management: Basic v2 by default (Standard v2 only for VNet integration or private endpoints).
- AI model: `gpt-6-luna` (DataZoneStandard, EU) on the environment's Foundry account; check quota before changing it.
- Decision model: `Microsoft-Decision-1` (deployment `decision-1`, DataZoneStandard) for anomaly detection; switch it off with `ANOMALY_DETECTION_ENABLED=false`. Tune detection with dry runs, not by trial in the environment.

## Security (non-negotiable)

- **Passwordless:** managed identities and Entra ID for every data and control plane; local/key authentication disabled wherever the service allows it. No secrets, keys or connection strings in code, configuration, IaC outputs or CI. Exceptions only as documented in ADR-0005.
- Users authenticate with Entra ID; app roles `DeviceData.Read` (query) and `DeviceData.Write` (edit, implies read). Services validate tokens themselves even behind API Management.
- The AI agent is read-only and acts with the caller's token; it can never see more than the user.
- **Roles:** prefer a built-in role when it includes the needed permissions (e.g. *Foundry User*, referenced by id, for any Foundry model call); create a custom role only when no built-in role fits.

## Stack and local tooling

| Area | Stack | Commands |
|---|---|---|
| Platform services | Java 25, Spring Boot 4, Maven wrapper | `export JAVA_HOME=~/.local/jdks/jdk-25.0.5.0.2+1 PATH=$JAVA_HOME/bin:$PATH`, then `./mvnw -pl services/<svc> -am verify` |
| Integration tests | Testcontainers on **Podman** (no Docker locally; CI uses the runner's Docker) | `DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true` |
| Portal | React, TypeScript, Vite, Fluent UI v9 | `npm run lint`, `npx tsc -b`, `npm test`, `bash scripts/e2e-podman.sh` (offline Playwright), `bash scripts/e2e-environment.sh` (against sdp-dev) |
| AI agent | Python 3.13, uv, FastAPI, Microsoft Agent Framework | `uv run pytest`, `uv run ruff check src tests`, `uv run --group eval python evals/run_eval.py` |
| Anomaly detector and site digest | Python 3.13, uv, psycopg, httpx, openai | `uv run pytest`, `uv run pytest -m live` (after `scripts/anomaly-env.sh`), `uv run anomaly-detector --dry-run --days 7 [--classify]`, `uv run site-digest --dry-run --site <id>` |
| Infrastructure | Terraform via azd | `terraform -chdir=infra fmt -recursive`, `terraform -chdir=infra validate` |

- OpenAPI documents are generated from code and committed (`services/*/openapi.json`, `agents/device-analyst/openapi.json`); a test fails when they drift. API Management imports them.
- Images are built remotely in Azure Container Registry by azd (`remoteBuild: true`).
- **CI** (`.github/workflows/ci.yml`) runs on every push to `main`; keep it green and keep each job reproducible with local commands (README, *Continuous integration*). No secrets or cloud credentials in workflows; pin third-party actions to commit SHAs. Fix vulnerable dependencies rather than accepting them; an acceptance in `.trivyignore.yaml` needs the user's agreement, a justification and an expiry.

## GitHub Copilot cloud agent

Work can be split between the cloud agent (no Azure access) and a session with access to `sdp-dev`. Its environment comes from `.github/workflows/copilot-setup-steps.yml` (JDK 25, Node 24, uv, Terraform, OpenSpec CLI, dependencies and test images fetched in advance).

- **Only the tasks marked [cloud agent]** in the change's `tasks.md`: implement them in order with the `openspec-apply-change` skill, verify each with the check it names, tick it, and commit it (conventional commit with the Copilot trailer) on the PR branch. Never tick a task marked **[Azure]**, and do not run `azd`, `az` or anything that needs credentials.
- **Docker, not Podman**, in that environment: Testcontainers uses the runner's Docker (no `DOCKER_HOST`); run the offline Playwright suite as CI does (`docker run --rm --network=none -e CI=1 -v "$PWD:/work" -w /work mcr.microsoft.com/playwright:v<version>-noble npx playwright test` in `web/portal`).
- **Models are mocked** in tests; live behaviour is checked by the [Azure] tasks. When the design is unclear or a task cannot be verified offline, say so in the PR instead of guessing, and leave the task unticked.
- Keep the PR's CI green.

## Coding conventions

- Precise, complete changes; no unrelated refactoring. Comment only what needs clarification.
- Validation errors as "field + message" problem details (RFC 9457), e.g. `errors.min = ["must be lower than max"]`.
- Telemetry through OpenTelemetry to Application Insights (Entra-authenticated); custom metrics are pre-aggregated (no percentiles).

## Known pitfalls

- `az monitor app-insights query` defaults to the last hour: pass `--offset 7d` (or the range you need).
- The Azure CLI caches tokens per scope: after an app-role change, request `"<api-client-id>/.default"` to get a fresh token.
- `az containerapp update` may time out although it applied: check before retrying.
- An ARM-created Service Bus subscription has no `$Default` rule; the Service Bus emulator caps duplicate detection at 5 minutes.
- Testcontainers on Podman: one container start per JVM in Failsafe (`reuseForks=false`).
- Locally, use **Podman** for anything that needs a container engine (`docker` is not functional on the development machine). Scripts pick `podman`, then `docker`; `CONTAINER_CLI` overrides (CI sets `docker`).
- On a busy machine `az` calls can exceed azd's timeout (`AzureCLICredential: signal: killed`); retry, or deploy the portal with the Static Web Apps CLI.
- Application Insights counts 4xx responses as failed requests.
- KQL reserved words cannot be column aliases (e.g. `cycles=count()` fails with a vague "BadArgumentError: The request had some invalid properties"): rename the alias.
- Anomaly injections restart the simulator; an anomaly ending less than 15 minutes before the next one on the same device and metric extends it (one episode). Use other devices, or wait, between demo injections.
- `az containerapp job update` can hang after applying: check the image with `az containerapp job show`.
- LLM-written text from figures: give the model the counts it needs as figures (it miscounts lists), and check its numbers against the figures.
- Foundry deployment names must not contain the reserved word "Microsoft" (e.g. `decision-1` for `Microsoft-Decision-1`).
- `Cognitive Services OpenAI User` only covers Azure OpenAI models; other Foundry models (e.g. the decision API) need *Foundry User*. A `PermissionDenied` error names the missing data action.
