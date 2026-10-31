# Spec Delta

## MODIFIED Requirements

### Requirement: Battery forecast
For each assessed device reporting a battery voltage or level, the platform SHALL estimate the current level, the trend per day and the days left before the cut-off (2.5 V, or 10 % for a level) from the last 14 days of readings, weighting recent days more, and starting after the last change: a sudden change of the level (typically a replacement) or a lasting departure from the earlier trend (a steepening or a smaller step), a segment covering at least 12 hours.

#### Scenario: Declining battery
- **WHEN** a device's battery voltage falls steadily by 0.05 V a day and is at 2.65 V
- **THEN** its forecast gives about 3 days left

#### Scenario: Battery replaced
- **WHEN** a device's battery level rose sharply two days ago
- **THEN** its forecast only uses the readings since that rise

#### Scenario: Decline steepens
- **WHEN** a battery that lost 0.0015 V a day for two weeks has been losing 0.035 V a day for the last day
- **THEN** its forecast uses the steep recent decline (about 0.035 V a day) and its days left follow from it

#### Scenario: Small step
- **WHEN** a battery voltage stepped down by 0.06 V a day ago, over two hourly averages, and has declined by 0.0015 V a day since
- **THEN** its forecast gives the level after the step and a decline of about 0.0015 V a day

#### Scenario: Steady decline
- **WHEN** a battery has declined steadily for two weeks
- **THEN** its forecast uses the two weeks of readings

#### Scenario: Rounding step of a level
- **WHEN** a battery level reported in whole percent drops by one point after days at the same value
- **THEN** the forecast does not treat it as a departure from the trend

#### Scenario: Not enough data
- **WHEN** a device has less than 12 hours of battery readings since its last replacement
- **THEN** its forecast gives the current level without trend or days left
