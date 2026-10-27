# telemetry-ingestion Specification

## Purpose

Receives device messages, decodes them in batches and stores every decoded measurement exactly once with its history, registering devices automatically and quarantining messages that cannot be decoded without blocking the flow.

## Requirements

### Requirement: Batch processing of device messages
The system SHALL consume device messages in batches of configurable maximum size and maximum wait time, decode each message of the batch independently, persist the batch, and only then acknowledge (checkpoint) it.

#### Scenario: Batch persisted then acknowledged
- **WHEN** a batch of 200 decodable messages is received
- **THEN** 200 measurements are persisted and the batch position is acknowledged after persistence

#### Scenario: Storage unavailable
- **WHEN** the database is unavailable while a batch is being persisted
- **THEN** the batch is not acknowledged, it is retried with back-off, and no message is lost once the database is available again

### Requirement: Measurement content
Each persisted measurement SHALL contain the device id, the site id, the payload format, the measurement time (time the platform received the message, UTC), the processing time, a unique message key, the original payload bytes, the decoded metrics and attributes.

#### Scenario: Stored measurement is complete
- **WHEN** device `paris-01-ruuvi5-001` sends the official Ruuvi DF5 test vector
- **THEN** the stored measurement has device `paris-01-ruuvi5-001`, site `paris-01`, format `ruuvi-df5`, the original 24 bytes and temperature 24.3

### Requirement: Idempotent persistence
The system SHALL store a given device message at most once, identified by device id plus the device-provided message id (or the stream position when absent), even when it is redelivered or resent by the device — and therefore received at a different time — within 7 days.

#### Scenario: Duplicate delivery
- **WHEN** the same message (same message id) is delivered twice, including across a decoder restart
- **THEN** exactly one measurement exists for it

#### Scenario: Device resends a message later
- **WHEN** a device resends a message with the same message id 10 minutes after the first send
- **THEN** exactly one measurement exists for it, with the time of the first receipt

### Requirement: Device auto-registration
The system SHALL register a device the first time a message from it is persisted, recording its site, last payload format, first-seen and last-seen times, and SHALL update last-seen and last payload format on every subsequent message.

#### Scenario: First message from a new device
- **WHEN** device `lyon-01-bthome-004` sends its first decodable message
- **THEN** a device record exists with site `lyon-01`, format `bthome-v2`, and first-seen equal to last-seen equal to the measurement time

#### Scenario: Subsequent message
- **WHEN** an existing device sends a new message
- **THEN** its last-seen time is updated and its first-seen time is unchanged

### Requirement: Quarantine of undecodable messages
A message that cannot be decoded, or that lacks its device identity or the site of its device, SHALL be stored as a rejected message with device id (when known), receipt time, original payload, reason code and detail, and SHALL NOT prevent the other messages of the batch from being persisted or the batch from being acknowledged.

#### Scenario: Corrupted message in a batch
- **WHEN** a batch contains 99 valid messages and 1 message with leading byte `0x7F`
- **THEN** 99 measurements are stored, 1 rejected message with reason `UNKNOWN_FORMAT` is stored, and the batch is acknowledged

#### Scenario: Message without site
- **WHEN** a decodable message arrives without a site identifier, or with an invalid one
- **THEN** it is stored as rejected with reason `MISSING_SITE`

#### Scenario: Message without device identity
- **WHEN** a message arrives without a device identifier
- **THEN** it is stored as rejected with reason `MISSING_DEVICE`

### Requirement: Ingestion latency and throughput
The system SHALL make a measurement queryable in the database within 60 seconds of the platform receiving it, at a sustained rate of at least 100 messages per second.

#### Scenario: Latency under nominal load
- **WHEN** the simulator sends 100 messages per second for 10 minutes
- **THEN** the 99th percentile of (persisted time − receipt time) is below 60 seconds

### Requirement: History retention
The system SHALL retain measurements and rejected messages for a configurable period of at least 30 days and SHALL remove older data automatically.

#### Scenario: Data older than retention
- **WHEN** retention is 30 days and measurements are 31 days old
- **THEN** they are removed by the automatic retention process without manual action

### Requirement: Accept only messages from trusted devices
The system SHALL only accept messages from devices authenticated with an X.509 certificate chained to an enabled site certificate authority.

#### Scenario: Device with untrusted certificate
- **WHEN** a device presents a certificate not issued by any enabled site certificate authority
- **THEN** its provisioning is refused and no message from it is stored
