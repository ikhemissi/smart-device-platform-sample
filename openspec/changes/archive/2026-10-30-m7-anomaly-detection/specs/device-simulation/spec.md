# Spec Delta

## MODIFIED Requirements

### Requirement: Realistic readings
Simulated readings SHALL evolve plausibly over time: temperature and humidity follow a daily cycle with noise per device, pressure drifts slowly like the weather and stays continuous when the simulator restarts, battery voltage/level decreases slowly, sequence/packet counters increase, and all values stay within each format's valid range.

#### Scenario: Continuous series
- **WHEN** a device reports 100 consecutive readings
- **THEN** consecutive temperature values differ by less than 1 °C except during an injected anomaly

#### Scenario: Restart
- **WHEN** the simulator restarts (for example to inject an anomaly)
- **THEN** each device's pressure continues from its previous level instead of jumping, so that anomaly detection does not flag the restart

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
