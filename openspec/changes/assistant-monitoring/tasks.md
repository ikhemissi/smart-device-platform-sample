# Tasks: AI assistant monitoring

## 1. Agent telemetry

- [x] 1.1 Record the `sdp.agent.turn` span (first-text time, outcome, tool calls, device context, pseudonymous user) in the agent (design D1); verify with unit tests on an in-memory span exporter for an answered turn with one tool call, a failed turn and a timed-out turn
- [x] 1.2 Give `ApiError` a kind and set `sdp.agent.tool.error` on the tool span when a tool returns an error (design D2); verify with a unit test that a not-found device records `not_found` on the `execute_tool` span
- [x] 1.3 Document the new attributes in the agent README; deploy the agent and verify in Application Insights that a question with an unknown device produces a turn span with `first_text_ms`, `outcome = answered`, one tool call and a `not_found` tool error

## 2. Workbook section

- [x] 2.1 Add the six queries of design D3 as `.kql` files and the section of design D4 to the workbook; verify `terraform validate` (including the workbook preconditions) and `scripts/check-workbook-queries.sh` against `sdp-dev`, after generating activity (a few questions, an unknown device, a rate-limited message), showing non-zero questions, tokens, tool calls, a `not_found` error and a 429
- [ ] 2.2 Run `azd provision --preview` (only the workbook changes), then `azd provision`; verify the section in the Azure portal with the user (indicators, charts, failures table, platform-wide label)
- [x] 2.3 Update the README (Operate), the M6 demo guide (Behind the scenes) and the implementation guidelines §5 (workbook contents); verify the links and that the guide's steps match the workbook

## Workflow follow-up

- Archive the change after review so the platform-observability and device-analyst-agent specs include the assistant monitoring.
