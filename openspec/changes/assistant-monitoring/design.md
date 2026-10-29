# Design: AI assistant monitoring

## Context

See proposal.md for the motivation and the delta specs for the required behaviour. Current state:

- The agent exports OpenTelemetry to Application Insights (cloud role `sdp-device-analyst`, 100 % sampling): the FastAPI request (`POST …/messages`, which lasts the whole streamed answer), `invoke_agent device-analyst` with the turn's token usage (`gen_ai.usage.input_tokens` / `output_tokens`), one `chat gpt-6-luna` span per model call, one `execute_tool <name>` span per tool call, and the httpx calls to API Management, all in one operation.
- Tools turn API errors into messages for the model, so their spans succeed; nothing records the time to the first text.
- The workbook is Terraform (`infra/modules/monitoring`): one `.kql` file per query, items assembled in `main.tf`, preconditions checking that every query uses the time range and has a file. Site and device filters only apply to device-level figures ([platform-observability spec](../../specs/platform-observability/spec.md)).
- Conventions: observability in the [implementation guidelines](../../../docs/architecture/implementation-guidelines.md) §5; prompts and answers are only captured when content capture is enabled (M6 design D7).

## Goals / Non-Goals

**Goals:** real percentiles for the time to first text (span attributes, not pre-aggregated metrics); no personal data in the new telemetry; reuse the workbook's file-per-query pattern and checks.

**Non-Goals:** quality scores in the workbook (the evaluation stays an offline run); cost in currency (tokens only); alert rules on these figures.

## Decisions

### D1. One `sdp.agent.turn` span per question
The API layer (the SSE stream of a message) opens an internal span `sdp.agent.turn` around the agent run; it becomes the parent of `invoke_agent`. It lives there rather than in `Analyst.run` because only the API knows how a turn ended (its 110 s `asyncio.timeout`, an exception, or the client disconnecting). Attributes:

| Attribute | Value |
|---|---|
| `sdp.agent.first_text_ms` | milliseconds from the start of the turn in the agent to the first text delta (absent when no text was produced) |
| `sdp.agent.outcome` | `answered`, `failed` (exception), `timeout` (110 s limit) or `cancelled` (client disconnected before the end) |
| `sdp.agent.tool_calls` | number of tool calls of the turn (as announced in the stream) |
| `sdp.agent.device_context` | whether the question carried a device context |
| `enduser.pseudo.id` | SHA-256 of the user's object id, truncated: counts distinct users without storing who they are |

The span status is set to error for `failed` and `timeout`. The time is measured in the agent, so it excludes the gateway and network (about 0.1–0.3 s less than the user perceives; the evaluation keeps measuring client-side).

Alternatives: an OpenTelemetry histogram metric — Application Insights pre-aggregates custom metrics (no percentiles); an attribute on the FastAPI request span — less explicit and tied to the web framework.

### D2. Tool error kinds
`ApiError` gets a `kind` (`not_found`, `not_permitted`, `invalid_request`, `unavailable`, `rate_limited`, `failed`). When a tool returns an error to the model, it sets `sdp.agent.tool.error = <kind>` on the current span, which is the Agent Framework's `execute_tool` span. The tool still returns the error to the model (behaviour unchanged).

### D3. Queries
All from Application Insights, filtered on `cloud_RoleName == "sdp-device-analyst"` (plus the gateway's requests to `/agent/` for 429s it may return):

| Query | Source |
|---|---|
| `assistant-indicators` (tiles) | `sdp.agent.turn` (questions, users, first-text percentiles and share ≤ 5 s, failed turns, tool calls), `invoke_agent` (tokens), requests with result code ≥ 400 (rejected, of which 429) |
| `assistant-trend` | questions and failed turns per hour |
| `assistant-first-text` | median and 95th percentile per hour |
| `assistant-tokens` | input and output tokens per hour |
| `assistant-tools` | tool calls by tool (succeeded and failed) |
| `assistant-tool-errors` | failed tool calls by kind |
| `assistant-failures` | latest failed turns and requests, with `operation_Id` |

Every query returns rows (zeros) when there is no activity, so the tiles show 0 rather than "no data".

### D4. Workbook layout
A new section after *Critical components*: a header "AI assistant" stating it is platform-wide (not affected by the site and device filters), the indicator tiles, then two items per row (questions/failures and first text; tokens and tool usage; tool errors by kind and the failures table). The existing preconditions cover the new queries unchanged.

## Risks / Trade-offs

- [Turn spans are new: the section is empty for turns before the redeployment] → acceptable; the demo guide asks a few questions first.
- [Pseudonymous user id could be re-identified by someone holding the object ids] → truncated hash without salt is only pseudonymisation; documented, and the figure is a distinct count only.
- [More spans per turn] → one extra span per question; negligible.

## Migration Plan

Deploy the agent (`azd deploy device-analyst`), then `azd provision --preview` / `azd provision` for the workbook. Rollback: redeploy the previous agent image; the workbook section shows empty charts without the turn spans.
