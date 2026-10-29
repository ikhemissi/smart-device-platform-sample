# Spec Delta

## ADDED Requirements

### Requirement: AI assistant indicators
The workbook SHALL show, in a section labelled as platform-wide, the AI assistant's questions, distinct users, time to first answer text (median, 95th percentile and share within 5 seconds), input and output tokens, tool calls, failed turns and rejected requests (including rate limiting).

#### Scenario: Questions asked
- **WHEN** users asked questions during the period
- **THEN** the questions, users, tokens and tool calls indicators count them, and the time to first text shows their median and 95th percentile and the share answered within 5 seconds

#### Scenario: No assistant activity
- **WHEN** nobody used the assistant during the period
- **THEN** the indicators show zero questions and the charts are empty instead of failing

### Requirement: AI assistant trends and failures
The AI assistant section SHALL chart questions and failures, time to first text, and tokens per hour, tool calls by tool and tool errors by kind, and SHALL list the latest failed turns and requests with the operation id that opens the end-to-end transaction.

#### Scenario: Tool usage
- **WHEN** questions caused tool calls, one of which failed because a device was not found
- **THEN** the tool usage chart counts the calls per tool and the tool errors chart shows one "not found" error

#### Scenario: Rate limiting
- **WHEN** a user's message was rejected for exceeding the message rate limit
- **THEN** the rejected requests indicator counts it and the latest failures list shows it with its operation id

## MODIFIED Requirements

### Requirement: Operations workbook
Each environment SHALL include, deployed with its infrastructure, an Azure Monitor workbook that shows the environment's key indicators, latest errors, critical components and AI assistant activity on one page, for a selectable time range (default: last 24 hours). It SHALL show the environment it was deployed with without manual configuration, and SHALL be readable by anyone allowed to read the environment's monitoring data.

#### Scenario: Open the workbook
- **WHEN** an operator opens the operations workbook of an environment in the Azure portal
- **THEN** the key indicators, latest errors, critical component charts and AI assistant section of that environment are shown for the last 24 hours, with no parameter to fill in

#### Scenario: Time range
- **WHEN** the operator selects the last 7 days
- **THEN** every indicator, list and chart covers the last 7 days

### Requirement: Workbook filters
The workbook SHALL offer, next to the time range, multi-select filters on site, device type (from the payload format) and device id (default: all); the device list SHALL only offer devices of the selected sites and device types. These filters SHALL apply to the device-level figures; platform-wide figures SHALL state that they are not filtered.

#### Scenario: One site
- **WHEN** the operator selects site `paris-01`
- **THEN** devices reporting and measurements stored only count `paris-01` devices, and the device filter only lists `paris-01` devices

#### Scenario: Platform-wide figures
- **WHEN** a site is selected
- **THEN** API, cache, alerting, errors, AI assistant and Azure resource charts are unchanged and labelled as platform-wide, and the ingestion latency only follows the device type filter

#### Scenario: One device
- **WHEN** the operator selects one device
- **THEN** devices reporting is 1 and the measurements trend shows that device's messages only
