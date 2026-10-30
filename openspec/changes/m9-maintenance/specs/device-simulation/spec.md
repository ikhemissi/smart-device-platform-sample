# Spec Delta

## MODIFIED Requirements

### Requirement: Realistic readings
Simulated readings SHALL evolve plausibly over time: temperature and humidity follow a daily cycle with noise per device, pressure drifts slowly like the weather, battery voltage/level decreases slowly, sequence/packet counters increase, and all values stay within each format's valid range. Pressure and battery SHALL be computed from the time so that they stay continuous when the simulator restarts. Configured aging devices SHALL drain their battery from full to their cut-off in about two weeks, then restart with a new battery.

#### Scenario: Continuous series
- **WHEN** a device reports 100 consecutive readings
- **THEN** consecutive temperature values differ by less than 1 °C except during an injected anomaly

#### Scenario: Restart
- **WHEN** the simulator restarts (for example to inject an anomaly)
- **THEN** each device's pressure and battery continue from their previous levels instead of jumping, so that anomaly detection and maintenance forecasts do not flag the restart

#### Scenario: Aging device
- **WHEN** a device is configured as aging
- **THEN** its battery falls by about 0.035 V a day until it reaches its cut-off (about two weeks from a new battery), then rises to a new battery's level
