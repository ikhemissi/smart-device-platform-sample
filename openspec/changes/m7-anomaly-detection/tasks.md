# Tasks: M7 — Anomaly detection

## 1. Data and infrastructure

- [x] 1.1 Migration V8 (`sdp.anomalies`, `sdp_insights` role, `sdp_catalog` grants) and anomaly retention in the maintenance task; verify with db-migrator integration tests (privileges of both roles, retention)
- [x] 1.2 Terraform: Decision-1 deployment, `id-anomaly-detector` identity and roles, PostgreSQL principal, job (placeholder image), settings (`ANOMALY_*`, `DECISION_MODEL_CAPACITY`) with the feature switch; quota preflight for the decision model; verify `azd provision --preview`, `azd provision`, then a decision API call with an Entra token (and that key access is refused), and with the feature switched off a plan without the deployment and the job
- [x] 1.3 `scripts/build-anomaly-detector-image.sh` and the post-provision hook step; verify the job runs with the built image (a cycle with no candidate exits successfully)

## 2. Detector

- [x] 2.1 Scaffold `agents/anomaly-detector` (uv, ruff, pytest, Dockerfile, settings, PostgreSQL access with an Entra token); verify tests and a local connection to `sdp-dev`
- [x] 2.2 Baseline and candidate detection (deviation, flat, jump, episodes; design D2); verify unit tests on synthetic series (daily cycle not flagged, a 10 °C rise flagged, a flat line flagged, an episode extended)
- [ ] 2.3 Decision-1 client, classification and status (design D3), pending retry on failure; verify unit tests with a mocked decision API (confirmed, dismissed, to review, failure → pending then classified) and a live call on a real candidate state
- [ ] 2.4 Explanations with gpt-6-luna (design D4); verify unit tests (prompt contains only the state, failure leaves no explanation) and a live explanation of a real candidate
- [ ] 2.5 Cycle orchestration, persistence and OpenTelemetry (design D10), plus a dry-run mode (no write, no model call) over a past period; verify a local dry run against `sdp-dev` and unit tests of the cycle with fakes
- [ ] 2.6 Tune `ANOMALY_DEVIATION` and `ANOMALY_CONFIDENCE` with dry runs over the last 7 days of `sdp-dev` (design D11); record the values and counts in the design and the README; verify the chosen defaults give a few confirmed anomalies per day on `paris-01`
- [ ] 2.7 Deploy the job and inject a 35 °C anomaly on a `paris-01` device with the simulator; verify a confirmed real event with explanation appears within 10 minutes, and its cycle trace in Application Insights

## 3. Platform API

- [ ] 3.1 device-api: list, device and review endpoints (design D7), OpenAPI regenerated; verify integration tests (filters, default status, 403 for Readers reviewing, validation errors) and the operations through API Management after provisioning

## 4. Portal

- [ ] 4.1 `anomaliesEnabled` and `anomalySites` in `config.json`; device page *Anomalies* card; verify component tests (confirmed, to review, not analysed site, feature off)
- [ ] 4.2 *Anomalies* page with filters and the review dialog for Editors; verify component tests and the offline Playwright suite (including accessibility)
- [ ] 4.3 Deploy the portal; verify with the user in the environment (device card and page show the anomaly of task 2.7; review it)

## 5. Assistant

- [ ] 5.1 `list_anomalies` tool (design D9); verify unit tests and, after deployment, the question "Anything unusual at paris-01 today?" answered with the recorded anomalies

## 6. CI, documentation and demo

- [ ] 6.1 CI: the detector's lint, tests and image build, and its image in the vulnerability scan; verify a green run on `main`
- [ ] 6.2 README (capability, flow diagram, settings), detector README, AGENTS.md and implementation guidelines (§10); verify links and commands
- [ ] 6.3 Demo guide `docs/demo/m7-anomaly-detection.md`; verify a dry run follows it as written (with the user for the portal steps)

## Workflow follow-up

- Archive the change after review so `anomaly-detection` becomes a baseline spec.
