# Spec Delta

## MODIFIED Requirements

### Requirement: Observability and evaluation
Each agent turn SHALL be traced end to end (model calls with token usage, tool calls, API calls) in Application Insights, SHALL record its time to first answer text, outcome (answered, failed, timed out) and number of tool calls, failed tool calls SHALL record an error kind (not found, not permitted, invalid request, unavailable, rate limited), and an evaluation dataset covering the PRD example questions SHALL be runnable to score groundedness, relevance and tool-call correctness.

#### Scenario: Trace of a turn
- **WHEN** a user asks a question that triggers two tool calls
- **THEN** Application Insights shows one trace with the model invocations (with token counts), both tool calls and the corresponding platform API requests

#### Scenario: Turn telemetry
- **WHEN** a question is answered after one tool call whose device was not found
- **THEN** the turn's telemetry holds its time to first text, the outcome "answered" and one tool call, and that tool call records the error kind "not found"
