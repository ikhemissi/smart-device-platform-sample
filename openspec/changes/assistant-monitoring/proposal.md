# Proposal: AI assistant monitoring

## Why

The AI assistant (M6, [PRD-006](../../../docs/prd/PRD-006-m6-ai-analyst.md)) is now part of the demonstrator, but the operations workbook does not show it, although [PRD-000 §7](../../../docs/prd/PRD-000-product-overview.md) asks that every user action can be followed in the monitoring tooling. Its usage, responsiveness (PRD-006: first words within 5 seconds), cost drivers (tokens) and errors are only visible today by writing KQL. Also, one of the key figures, the time to the first text, is not recorded at all: it was only measured by the evaluation script, on the client side.

## What Changes

- The agent records each turn as a span with its time to first text, outcome (answered, failed, timed out), number of tool calls and whether it had a device context, and marks failed tool calls with an error kind (not found, not permitted, invalid request, unavailable, rate limited). Model calls, token usage and tool calls are already traced.
- A new **AI assistant** section in the operations workbook (platform-wide, labelled as not affected by the site and device filters):
  - indicators: questions, distinct users, time to first text (median and 95th percentile, share within 5 s), input and output tokens, tool calls, failed turns and rejected requests (including rate limiting);
  - questions and failures per hour, and time to first text per hour;
  - tokens per hour;
  - tool usage by tool, and tool errors by kind;
  - latest failed turns and requests, with the operation id to open the end-to-end transaction.
- `scripts/check-workbook-queries.sh` covers the new queries; the README and the M6 demo guide point to the section.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `platform-observability`: the operations workbook gains an AI assistant section (new requirement), the workbook requirement lists it, and the filter requirement treats it as platform-wide.
- `device-analyst-agent`: the observability requirement adds per-turn telemetry (time to first text, outcome, tool count) and tool error kinds.

## Impact

- `agents/device-analyst` (telemetry only; no API or behaviour change), redeployed with `azd deploy device-analyst`.
- `infra/modules/monitoring` (new `.kql` queries and workbook items), applied with `azd provision`; no new Azure resource.
- No new data collection beyond metadata: prompts and answers stay excluded unless content capture is enabled.
