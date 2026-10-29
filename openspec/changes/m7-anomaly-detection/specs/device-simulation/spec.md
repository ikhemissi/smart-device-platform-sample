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
