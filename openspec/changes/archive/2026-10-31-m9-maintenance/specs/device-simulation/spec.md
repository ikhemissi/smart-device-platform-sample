# Spec Delta

## MODIFIED Requirements

### Requirement: Realistic readings
Simulated readings SHALL evolve plausibly within each format's valid range: a daily cycle with noise for temperature and humidity, a slow weather-like drift for pressure, a slowly decreasing battery, increasing counters. Pressure and battery SHALL stay continuous across simulator restarts; configured aging devices SHALL drain their battery to the cut-off in about two weeks, then get a new one.

#### Scenario: Continuous series
- **WHEN** a device reports 100 consecutive readings
- **THEN** consecutive temperature values differ by less than 1 °C except during an injected anomaly

#### Scenario: Restart
- **WHEN** the simulator restarts (for example to inject an anomaly)
- **THEN** each device's pressure and battery continue from their previous levels instead of jumping, so that anomaly detection and maintenance forecasts do not flag the restart

#### Scenario: Aging device
- **WHEN** a device is configured as aging
- **THEN** its battery falls by about 0.035 V a day until it reaches its cut-off (about two weeks from a new battery), then rises to a new battery's level
