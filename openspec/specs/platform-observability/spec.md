# platform-observability Specification

## Purpose

Makes every data flow and user action observable in Application Insights through OpenTelemetry, so that a measurement or a request can be followed end to end during the demo and problems can be diagnosed.

## Requirements

### Requirement: OpenTelemetry export to Application Insights
Every platform workload SHALL export traces, metrics and logs with OpenTelemetry to the environment's Application Insights, authenticating with its managed identity, and SHALL identify itself with `service.name` = `sdp-<service>` and `deployment.environment` = the environment name.

#### Scenario: Service appears in the application map
- **WHEN** the decoder processes messages
- **THEN** `sdp-telemetry-decoder` appears in Application Insights with requests, dependencies, logs and metrics

### Requirement: End-to-end message correlation
A device message SHALL be traceable from the simulator's send operation to its persistence: the decoder's batch processing span SHALL link to the trace context carried by each message, and spans SHALL carry `sdp.device.id`, `sdp.site.id` and `sdp.payload.format` where applicable.

#### Scenario: Follow a message
- **WHEN** an operator searches traces by `sdp.device.id` for a simulated device
- **THEN** they find the send span and a decoder batch span linked to it, including the database write

### Requirement: Ingestion metrics
The decoder SHALL emit metrics for messages received, decoded and rejected (dimensioned by format and reason), batch size, and end-to-end latency from platform receipt to persistence.

#### Scenario: Rejections are visible
- **WHEN** corrupted payloads are injected
- **THEN** the rejected-messages metric increases with reason `UNKNOWN_FORMAT` or `MALFORMED_PAYLOAD`

### Requirement: Correlated structured logs
Workload logs SHALL be structured and include the active trace and span identifiers.

#### Scenario: Log to trace navigation
- **WHEN** a rejected-message warning is logged
- **THEN** the log entry in Application Insights carries the trace id of the batch processing span

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

### Requirement: Key indicators
The workbook SHALL show, in its top panel right below the time range and filters, devices reporting, measurements stored and rejected (by reason), ingestion latency (average and maximum), API requests with failure rate and 95th percentile duration, history cache hit ratio, alerts opened and closed, and alert emails sent and failed.

#### Scenario: Ingestion activity
- **WHEN** the simulator is running
- **THEN** devices reporting and measurements stored are greater than zero and the ingestion latency is shown

#### Scenario: Alerting activity
- **WHEN** an alert was opened and its email sent during the period
- **THEN** the alerts opened and emails sent indicators count it

### Requirement: Latest errors
The workbook SHALL list the most recent exceptions, failed requests and failed dependencies of all platform services, newest first, each with its service, time, message and operation id, and SHALL show rejected device messages by reason over time.

#### Scenario: Errors appear
- **WHEN** a service records an exception or a failed request
- **THEN** it appears in the latest errors with its service, time, message and operation id

### Requirement: Critical components
The workbook SHALL chart the Azure Monitor metrics of the PostgreSQL server (CPU, memory, storage, active connections), the IoT Hub (connected devices, telemetry messages sent, throttling errors), the Event Hubs namespace (incoming and outgoing messages, throttled requests, server errors), the Service Bus namespace (active and dead-lettered messages) and API Management (requests, duration).

#### Scenario: Ingestion is visible end to end
- **WHEN** the simulator is running
- **THEN** the IoT Hub telemetry, Event Hubs incoming messages and PostgreSQL charts all show activity for the same period

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
