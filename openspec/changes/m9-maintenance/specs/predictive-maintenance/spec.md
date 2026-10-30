# Spec Delta

## Purpose

Anticipates device interventions: forecasts each device's battery, computes reporting signals, asks a decision model for the probability that the device needs an intervention within 7 days, and keeps a maintenance queue, most likely first, with work orders and Editors' status.

## ADDED Requirements

### Requirement: Assessed sites
The platform SHALL assess the devices of the sites configured for the environment (default: all sites), at least every hour.

#### Scenario: Site not configured
- **WHEN** a device belongs to a site that is not configured
- **THEN** it has no assessment and never appears in the queue

### Requirement: Battery forecast
For each assessed device reporting a battery voltage or level, the platform SHALL estimate the current level, the trend per day and the days left before the cut-off (2.5 V, or 10 % for a level), from the device's readings of the last 14 days, giving more weight to the recent days, and starting after the last sudden change of the level (typically a battery replacement).

#### Scenario: Declining battery
- **WHEN** a device's battery voltage falls steadily by 0.05 V a day and is at 2.65 V
- **THEN** its forecast gives about 3 days left

#### Scenario: Battery replaced
- **WHEN** a device's battery level rose sharply two days ago
- **THEN** its forecast only uses the readings since that rise

#### Scenario: Not enough data
- **WHEN** a device has less than 1 day of battery readings since its last replacement
- **THEN** its forecast gives the current level without trend or days left

### Requirement: Reporting signals
For each assessed device, the platform SHALL compute signals of degrading communication: time since the last message, the longest gap and the typical interval between messages over the last 24 hours compared with the previous 7 days, and the number of rejected messages over the last 24 hours.

#### Scenario: Growing gaps
- **WHEN** a device that reported every 30 seconds now leaves gaps of 10 minutes
- **THEN** its signals show the longer gaps compared with the previous week

### Requirement: Intervention probability
For each assessed device, the platform SHALL obtain from the decision model the probability that the device needs an intervention within 7 days, from its battery forecast and reporting signals only, and SHALL record it with the model, its version and the signals used.

#### Scenario: Model failure
- **WHEN** the decision model fails for a device
- **THEN** the device keeps its previous assessment, the failure is visible in the monitoring tools, and the next run retries

### Requirement: Maintenance queue
A device whose intervention probability is at or above the configured confidence (default 0.5) SHALL be in the maintenance queue with its probability, the main reasons (as computed figures) and a work order of at most four sentences written from those figures only; the queue SHALL be listed most likely first. A device SHALL have at most one item open or planned at a time; its probability and reasons SHALL be updated by later assessments.

#### Scenario: Aging battery
- **WHEN** a device's battery forecast gives 3 days left
- **THEN** the device is in the queue with a high probability and a work order naming the device, the battery level and the days left

#### Scenario: Probability falls
- **WHEN** an open item's device is assessed below the confidence (e.g. its battery was replaced)
- **THEN** the item is closed as resolved by the platform and leaves the queue

### Requirement: Editor status
Editors SHALL be able to mark a queue item planned or done with an optional comment; Readers SHALL receive 403. A done item SHALL leave the queue, and the device SHALL NOT be queued again during the 7 days after it was marked done.

#### Scenario: Visit done
- **WHEN** an Editor marks an item done with the comment "battery replaced"
- **THEN** the item leaves the queue, keeps who did it, when and the comment, and the device is not queued again for 7 days

### Requirement: Maintenance API
The platform API SHALL let Readers list the queue (filters: site, status open, planned, done, resolved; default open and planned; most likely first) and get a device's latest assessment with its current item; and SHALL let Editors change an item's status.

#### Scenario: Device without battery
- **WHEN** a Reader gets the assessment of a device that reports no battery
- **THEN** the response has its reporting signals and probability, and no battery forecast

### Requirement: Availability per environment
Maintenance SHALL be switchable per environment; when it is off, no assessment runs, the portal hides the maintenance features, the assistant has no maintenance tool, the site digest omits the queue, and the rest of the platform is unaffected.

#### Scenario: Maintenance disabled
- **WHEN** maintenance is disabled in the environment
- **THEN** the navigation has no Maintenance entry and device pages show no battery forecast

### Requirement: Maintenance observability
Each assessment run SHALL be traced in Application Insights with the devices assessed, the decision model and LLM calls (with token usage), the items opened, updated and closed, and failures.

#### Scenario: Trace of a run
- **WHEN** a run assesses the devices of a site and opens one item
- **THEN** Application Insights shows one request for the run with the decision calls and the work order call as children
