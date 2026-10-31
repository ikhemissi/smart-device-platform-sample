# device-analyst-agent Specification

## Purpose

Lets users analyse devices, measurements and alerts in natural language through a conversational assistant whose answers are grounded in platform data, restricted to what the user may see, and never modify anything.

## Requirements

### Requirement: Conversation API
The API SHALL let Readers create a conversation (`POST /api/v1/agent/conversations`), send a message with optional context (`deviceId`) and receive the answer as a Server-Sent Events stream (`POST /api/v1/agent/conversations/{id}/messages`), read a conversation's messages (`GET`) and delete it (`DELETE`).

#### Scenario: Streaming answer
- **WHEN** a Reader sends "How many devices are reporting at site paris-01?"
- **THEN** the response streams `delta` events with answer text, optional `tool` events naming the data consulted, and ends with a `done` event containing citations

### Requirement: Conversations are private and expire
A conversation SHALL be accessible only to the user who created it (others receive 404), SHALL keep context for follow-up questions, and SHALL expire 1 hour after its last message; a conversation holds at most 50 messages.

#### Scenario: Another user's conversation
- **WHEN** user B requests user A's conversation id
- **THEN** the response is 404

#### Scenario: Follow-up question
- **WHEN** after asking about `paris-01-ruuvi5-001` the user asks "and its humidity?"
- **THEN** the answer is about the humidity of `paris-01-ruuvi5-001`

### Requirement: Grounded answers with citations
Answers about devices, measurements or alerts SHALL be based on data retrieved during the conversation, SHALL cite the devices and periods used (citations link to device pages), and SHALL state when the needed data is unavailable rather than guessing.

#### Scenario: Unknown device
- **WHEN** a user asks about device `does-not-exist`
- **THEN** the assistant states it cannot find this device and suggests searching by name

#### Scenario: Trend question cites period
- **WHEN** a user asks how the temperature of a device evolved over the last 7 days
- **THEN** the answer describes the trend with figures and cites the device and the 7-day period

### Requirement: User-scoped, read-only data access
The assistant SHALL access platform data only through the platform API with the requesting user's permissions, SHALL use read-only operations exclusively, and SHALL decline requests to modify anything.

#### Scenario: Modification request
- **WHEN** a user (even an Editor) asks the assistant to delete a threshold
- **THEN** the assistant declines, explains it can only analyse data, and no mutating API call is made

#### Scenario: User without permission
- **WHEN** a user without `DeviceData.Read` calls the conversation API
- **THEN** the response is 403

### Requirement: Device context
When a message carries a `deviceId` context, the assistant SHALL treat that device as the subject of questions that do not name another device.

#### Scenario: Question from device page
- **WHEN** a user on the page of `lyon-01-bthome-004` asks "is the battery OK?"
- **THEN** the answer concerns the battery of `lyon-01-bthome-004`

### Requirement: Language and tone
The assistant SHALL answer in the language of the question (at least English and French) and use units as defined by the platform.

#### Scenario: French question
- **WHEN** a user asks "Quels capteurs de Lyon sont silencieux ?"
- **THEN** the answer is in French

### Requirement: Responsiveness and limits
For at least 90% of the evaluation questions, the first answer text SHALL arrive within 5 seconds. A turn SHALL perform at most 10 data look-ups, and requests per user SHALL be rate-limited (default 20 messages per minute) with HTTP 429 when exceeded.

#### Scenario: Rate limit
- **WHEN** a user sends 21 messages within one minute
- **THEN** the 21st request is answered with 429

### Requirement: Observability and evaluation
Each agent turn SHALL be traced end to end (model calls with token usage, tool calls, API calls) in Application Insights, SHALL record its time to first answer text, outcome (answered, failed, timed out) and number of tool calls, failed tool calls SHALL record an error kind (not found, not permitted, invalid request, unavailable, rate limited), and an evaluation dataset covering the PRD example questions SHALL be runnable to score groundedness, relevance and tool-call correctness.

#### Scenario: Trace of a turn
- **WHEN** a user asks a question that triggers two tool calls
- **THEN** Application Insights shows one trace with the model invocations (with token counts), both tool calls and the corresponding platform API requests

#### Scenario: Turn telemetry
- **WHEN** a question is answered after one tool call whose device was not found
- **THEN** the turn's telemetry holds its time to first text, the outcome "answered" and one tool call, and that tool call records the error kind "not found"

### Requirement: Assistant panel in the portal
The portal SHALL offer an assistant panel openable from any page, showing streamed answers, tool activity indicators, citations as links, a "new conversation" action and, on a device page, the current device as context.

#### Scenario: Ask from a device page
- **WHEN** a user opens the panel on a device page and asks a question
- **THEN** the panel shows the device as context, streams the answer and renders device citations as links

### Requirement: Anomaly questions
When anomaly detection is enabled, the assistant SHALL be able to list recent anomalies by site, device, status and period through the platform API with the user's permissions, and SHALL present their classification and confidence as the platform recorded them, citing the devices and periods.

#### Scenario: Anomalies at a site
- **WHEN** a user asks "Anything unusual at paris-01 today?"
- **THEN** the answer lists today's confirmed anomalies and the ones to review, with classifications, confidences, devices and periods, or says there were none

### Requirement: Site digest questions
When site digests are enabled, the assistant SHALL be able to return a site's latest digest through the platform API with the user's permissions, presenting its period, brief and figures as recorded and citing the site.

#### Scenario: Yesterday at a site
- **WHEN** a user asks "What happened at lyon-01 in the last day?"
- **THEN** the answer uses the site's latest digest, states its period and generation time, and cites the site

### Requirement: Maintenance questions
When maintenance is enabled, the assistant SHALL be able to list the maintenance queue by site and status through the platform API with the user's permissions, presenting each device's probability, days left and reasons as recorded and citing the devices.

#### Scenario: Visits this week
- **WHEN** a user asks "Which devices need a visit this week?"
- **THEN** the answer lists the queued devices most likely first, with probabilities, days left and reasons, or says the queue is empty
