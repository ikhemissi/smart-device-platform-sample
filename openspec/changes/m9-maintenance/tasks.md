# Tasks: M9 — Battery forecast and maintenance queue

Tasks marked **[cloud agent]** are implemented and verified offline (no Azure access) by the GitHub Copilot cloud agent on the PR branch, one conventional commit per task. Tasks marked **[Azure]** need the `sdp-dev` environment and are done afterwards (design D12).

## 0. Delegation

- [x] 0.1 [Azure] Branch, draft PR, PRD-009, this change, and the cloud agent setup (`copilot-setup-steps.yml`, OpenSpec agent, AGENTS.md section); verify the setup workflow runs green on the branch

## 1. Simulator, data and infrastructure

- [x] 1.1 [cloud agent] Simulator battery from the time with aging devices (design D11) and `SDP_SIM_AGING_DEVICES` (application.yaml, simulator README); verify device-fleet unit tests: continuity across a restart, aging device crossing 2.5 V within 16 days then a new battery, normal device above 2.75 V for 200 days, other values and MACs unchanged
- [x] 1.2 [cloud agent] Migration V10 (`sdp.device_maintenance`, `sdp.maintenance_items`, grants, design D7) and retention of closed items in the maintenance task; verify db-migrator integration tests (privileges of `sdp_insights` and `sdp_catalog`, one open or planned item per device, retention)
- [x] 1.3 [cloud agent] Terraform: job `caj-upkeep-<token>` (same image and identity, placeholder image, command `maintenance`), settings `MAINTENANCE_ENABLED`, `MAINTENANCE_SITES`, `MAINTENANCE_CONFIDENCE`, `SIMULATOR_AGING_DEVICES`, device-api / agent / digest job environment, Decision-1 deployment when anomaly detection or maintenance is enabled (and the quota preflight), output `UPKEEP_JOB_NAME` (`MAINTENANCE_JOB_NAME` is the database maintenance job, design D9), build script updating the three jobs, placeholder `maintenance` entry point exiting 0; verify `terraform fmt -check` and `terraform validate`, `bash -n` of the scripts, and the Python tests
- [ ] 1.4 [Azure] `azd provision --preview` (only the expected changes), `azd provision` (migration V10, image), deploy the simulator; verify with the feature switched off a plan without the job, and the aging devices' battery in `sdp-dev`

## 2. Maintenance job

- [x] 2.1 [cloud agent] Battery forecast (design D2) as pure functions; verify unit tests: steady decline gives the expected days left, a replacement (jump up) and a model change (jump down) start a new segment, recent days weigh more, less than 24 hours gives the level only, voltage and level cut-offs
- [x] 2.2 [cloud agent] Data loading and reporting signals (design D3) with plain-row loaders and pure computations; verify unit tests on seeded rows (gaps, silence, rejected messages, devices without battery)
- [x] 2.3 [cloud agent] Intervention probability (design D4) and work orders (design D6); verify unit tests with mocked HTTP (state holds the signals only, probability parsed, failure keeps the previous assessment, work order prompt and grounding check, failure leaves no work order)
- [ ] 2.4 [cloud agent] Run orchestration and queue lifecycle (design D5), storage, telemetry (request span before connecting, children per decision and work order) and `maintenance --dry-run [--site]` (prints assessments and the queue changes, writes nothing, `--no-models` skips model calls); verify unit tests with fakes (open, update, refresh work order after 24 h, resolve, done blocks for 7 days, one item per device)
- [ ] 2.5 [Azure] Live checks: a dry run against `sdp-dev` (forecasts of the aging devices, signals of all devices, live decision and work order); fix what the live data reveals
- [ ] 2.6 [Azure] Tune `MAINTENANCE_CONFIDENCE` with dry runs (aging devices near their cut-off queued, healthy devices not); record the result in this design and the detector README; deploy the job; verify the queue in `sdp-dev`, the run's trace, and that a second run updates rather than duplicates

## 3. Platform API

- [ ] 3.1 [cloud agent] device-api: queue list, device assessment and status change (design D8), OpenAPI regenerated; verify integration tests (default statuses, filters, ordering, device without battery, maintenance off or site not configured, 403 for Readers, 409 for done or resolved items, comment length)
- [ ] 3.2 [Azure] Deploy device-api, provision (API Management operations); verify the operations through API Management

## 4. Portal

- [ ] 4.1 [cloud agent] `maintenanceEnabled` in `config.json`; Maintenance page and the device page's *Battery and maintenance* card (design D9); verify component tests and the offline Playwright suite (including accessibility)
- [ ] 4.2 [Azure] Deploy the portal; verify with the user in the environment (queue with the aging devices, plan and close an item, device card)

## 5. Assistant and site digest

- [ ] 5.1 [cloud agent] `list_maintenance` tool (design D10); verify unit tests
- [ ] 5.2 [cloud agent] Digest figures and brief instructions include the maintenance queue (design D10); verify unit tests (figures with and without maintenance, grounding of the counts)
- [ ] 5.3 [Azure] Deploy the agent and the job image; verify "Which devices need a visit this week?" and a regenerated digest of a site with a queued device

## 6. CI, documentation and demo

- [ ] 6.1 [cloud agent] CI covers the new code; verify a green CI run on the PR
- [ ] 6.2 [cloud agent] README (capability, flow, settings), detector README, AGENTS.md, implementation guidelines (§10) and the demo guide `docs/demo/m9-maintenance.md` (draft); verify links and offline commands
- [ ] 6.3 [Azure] Verify the demo guide as written (with the user for the portal steps); merge the PR; archive the change
