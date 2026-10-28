# Spec Delta

## ADDED Requirements

### Requirement: Operations workbook
Each environment SHALL include, deployed with its infrastructure, an Azure Monitor workbook that shows the environment's key indicators, latest errors and critical components on one page, for a selectable time range (default: last 24 hours). It SHALL show the environment it was deployed with without manual configuration, and SHALL be readable by anyone allowed to read the environment's monitoring data.

#### Scenario: Open the workbook
- **WHEN** an operator opens the operations workbook of an environment in the Azure portal
- **THEN** the key indicators, latest errors and critical component charts of that environment are shown for the last 24 hours, with no parameter to fill in

#### Scenario: Time range
- **WHEN** the operator selects the last 7 days
- **THEN** every indicator, list and chart covers the last 7 days

### Requirement: Key indicators
The workbook SHALL show: devices reporting, measurements stored and rejected (by reason), ingestion latency (average and maximum), API requests with failure rate and 95th percentile duration, history cache hit ratio, alerts opened and closed, and alert emails sent and failed.

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
