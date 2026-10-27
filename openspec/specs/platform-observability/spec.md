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
