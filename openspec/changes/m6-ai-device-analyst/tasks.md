# Tasks: M6 — AI device analyst

## 1. Foundry and infrastructure

- [x] 1.1 Add AI Services (Foundry) account with local auth disabled, project, model deployment (`gpt-6-luna`, DataZoneStandard, variable model name/capacity) and Application Insights connection via Terraform; verify `azd provision` and that key-based access is refused
- [x] 1.2 Add the agent identity, roles (Foundry, Redis access policy) and container app; verify role assignments and a healthy deployment
- [x] 1.3 Add a quota preflight check for the configured model in `francecentral` to the `preprovision` hook; verify it fails fast with a clear message when quota is missing

## 2. Fleet analytics API

- [x] 2.1 Implement `GET /api/v1/fleet/metric-summary` (site/device grouping, first/last values, validation); verify the humidity-comparison, battery-overview and invalid-metric scenarios with Testcontainers
- [x] 2.2 Implement `GET /api/v1/fleet/status`; verify the silent-devices scenario
- [x] 2.3 Measure 7-day summaries on a 100-device dataset and publish through APIM; verify p95 < 3 s at the gateway

## 3. Agent service

- [x] 3.1 Scaffold `agents/device-analyst` (uv, FastAPI, Dockerfile, ruff, pytest) with JWT validation and role checks; verify tests for 401/403 and the user-without-permission scenario
- [x] 3.2 Implement read-only tools calling APIM with the caller's token, result compaction and error mapping; verify unit tests with a mocked API (incl. not found and truncation)
- [x] 3.3 Implement the agent (instructions, tool registration, max iterations, device context); verify a local run answers the "devices reporting at paris-01" question against the dev environment
- [x] 3.4 Implement conversation storage in Redis with ownership, sliding TTL and message cap; verify tests for the other-user 404, follow-up and expiry scenarios (fakeredis)
- [x] 3.5 Implement SSE streaming (`delta`, `tool`, `done` with citations); verify an integration test consumes a full stream
- [x] 3.6 Enable OpenTelemetry (Azure Monitor distro, Agent Framework instrumentation, httpx propagation); verify the trace-of-a-turn scenario in Application Insights
- [ ] 3.7 Document configuration, local run and evaluation in `agents/device-analyst/README.md`; verify commands run as written

## 4. Gateway

- [ ] 4.1 Add `/api/v1/agent/*` operations with unbuffered streaming, 120 s timeout, Read-role check and per-user rate limit (20/min) in APIM; verify streaming works through APIM and the 21st message returns 429

## 5. Portal

- [ ] 5.1 Implement the assistant panel (open from any page, streaming rendering, tool activity, citations as links, new conversation, device context on device pages, feature flag); verify component tests with a mocked SSE stream
- [ ] 5.2 Playwright smoke test: ask a question from a device page and follow a citation link; verify it passes against the environment

## 6. Evaluation and verification

- [ ] 6.1 Create `evals/questions.jsonl` covering PRD-006 example questions, the modification-refusal, unknown-device and French-language cases; verify the evaluation script runs and reports groundedness, relevance and tool-call accuracy
- [ ] 6.2 Run the evaluation on a seeded environment; verify ≥ 90% of questions stream first text within 5 s and record scores and thresholds in the README
- [ ] 6.3 Write the M6 demo guide `docs/demo/m6-ai-analyst.md`; verify a dry run follows it as written

## Workflow follow-up

- Archive the change after review so its specs become the baseline in `openspec/specs/`.
