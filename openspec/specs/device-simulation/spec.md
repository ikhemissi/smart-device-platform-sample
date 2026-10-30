# device-simulation Specification

## Purpose

Emulates a fleet of real-format sensors spread across several sites, so the platform can be demonstrated end to end without physical devices, including faulty messages and anomalous readings.

## Requirements

### Requirement: Configurable fleet
The simulator SHALL be configurable for: the list of sites, the number of devices per site, the mix of payload formats, the reporting interval with random jitter, and an optional run duration. Defaults SHALL be 3 sites (`paris-01`, `lyon-01`, `marseille-01`), 10 devices per site, an even mix of the four formats and a 30-second interval.

#### Scenario: Default run
- **WHEN** the simulator starts without configuration
- **THEN** 30 devices across 3 sites report every 30 seconds (± jitter), using all four formats

#### Scenario: Custom fleet
- **WHEN** the simulator is configured with 5 sites and 20 devices per site at a 5-second interval
- **THEN** 100 devices report every 5 seconds (± jitter)

### Requirement: Device identity and provisioning
Each simulated device SHALL use a deterministic id `<siteId>-<format>-<nnn>` (e.g. `paris-01-ruuvi5-007`), authenticate with an X.509 certificate whose subject CN is that id and which is issued by its site's certificate authority, and SHALL obtain its IoT Hub assignment through the provisioning service.

#### Scenario: Device provisions with site certificate
- **WHEN** a simulated device of site `lyon-01` starts for the first time
- **THEN** it is provisioned through the provisioning service with a certificate issued by the `lyon-01` CA and connects to the assigned hub

#### Scenario: Stable identity across restarts
- **WHEN** the simulator restarts with the same configuration
- **THEN** the same device ids are reused

### Requirement: Real-format payloads
The simulator SHALL encode each reading in the device's configured format exactly as defined by the payload-decoding capability (native frame for Ruuvi and BTHome, `0xCA` marker plus frame for Cayenne LPP) and send it as a binary message body.

#### Scenario: Cayenne device message
- **WHEN** a `cayenne-lpp` simulated device sends a reading
- **THEN** the message body starts with `0xCA` followed by a valid Cayenne LPP frame

### Requirement: Realistic readings
Simulated readings SHALL evolve plausibly over time: temperature and humidity follow a daily cycle with noise per device, pressure drifts slowly like the weather and stays continuous when the simulator restarts, battery voltage/level decreases slowly, sequence/packet counters increase, and all values stay within each format's valid range.

#### Scenario: Continuous series
- **WHEN** a device reports 100 consecutive readings
- **THEN** consecutive temperature values differ by less than 1 °C except during an injected anomaly

#### Scenario: Restart
- **WHEN** the simulator restarts (for example to inject an anomaly)
- **THEN** each device's pressure continues from its previous level instead of jumping, so that anomaly detection does not flag the restart

### Requirement: Message identity and trace context
Each message SHALL carry a unique message id (reused if the same message is resent) and a W3C `traceparent` application property.

#### Scenario: Message properties
- **WHEN** any simulated message is sent
- **THEN** it has a non-empty message id and a valid `traceparent` property

### Requirement: Fault and anomaly injection
The simulator SHALL support injecting, at a configurable rate or on demand, corrupted payloads (unknown leading byte, truncated frame) and anomalies (a metric of a given device forced to a given value for a given duration), either at once and held almost constant (like a faulty sensor) or reached gradually over a given ramp with the usual noise and released the same way (like a real change of the environment).

#### Scenario: Corrupted payload injection
- **WHEN** corruption rate is set to 1%
- **THEN** about 1% of messages carry an unknown leading byte or a truncated frame

#### Scenario: Temperature anomaly on demand
- **WHEN** an anomaly "temperature = 12 °C for 5 minutes" is requested for device `paris-01-ruuvi5-001`
- **THEN** that device reports 12 °C (± noise) for 5 minutes, then returns to its normal pattern

#### Scenario: Gradual anomaly
- **WHEN** an anomaly "temperature = 35 °C with a 5-minute ramp for 10 minutes" is requested
- **THEN** the device's temperature rises gradually to about 35 °C over 5 minutes, stays there with noise for 10 minutes, then returns gradually to its normal pattern over 5 minutes

### Requirement: Execution modes
The simulator SHALL run both locally (developer machine, using the developer's Entra identity to fetch certificates) and as a container in the platform environment, and SHALL stop gracefully, closing device connections, on termination.

#### Scenario: Graceful stop
- **WHEN** the simulator receives a termination signal
- **THEN** in-flight messages are completed or abandoned and all device connections are closed within 10 seconds
