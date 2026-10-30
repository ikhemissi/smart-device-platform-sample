# Spec Delta

## Purpose

Detects unusual readings on the analysed sites without configured thresholds, classifies them as real events, sensor faults or expected patterns with a calibrated confidence, explains the confident ones, and keeps a reviewable record of every automated decision.

## ADDED Requirements

### Requirement: Analysed sites
The platform SHALL analyse the devices of the sites configured for the environment (default: `paris-01`) and no others.

#### Scenario: Site not analysed
- **WHEN** a device of a site that is not configured reports an unusual reading
- **THEN** no anomaly is recorded for it

### Requirement: Candidate detection
At least every 5 minutes, the platform SHALL compare each analysed device's recent readings of each numeric metric with the device's own usual values for that time of day over the previous 7 days, and SHALL flag as candidates readings far from the usual values (beyond a configurable deviation), flat readings of a metric that usually varies, and sudden jumps between consecutive readings.

#### Scenario: Cold room warming up
- **WHEN** a device of an analysed site reports a temperature 10 °C above its usual level at that time for 5 minutes
- **THEN** an anomaly for that device and metric appears in the portal within 10 minutes, with the period, the observed values and the usual values

#### Scenario: Normal operation
- **WHEN** the demo fleet runs normally for a day
- **THEN** only a few anomalies are recorded for the analysed sites, not one every cycle

### Requirement: One anomaly per episode
Consecutive candidates of the same device and metric SHALL extend one anomaly rather than create new ones.

#### Scenario: Ongoing deviation
- **WHEN** the deviation continues for 30 minutes
- **THEN** the same anomaly is extended to cover the whole period instead of creating new ones

### Requirement: Classification with confidence
Each candidate SHALL be classified by the decision model as one of *real event*, *sensor fault*, *expected pattern* or *cannot tell*, from a description of the device, the metric, the recent readings, the usual values and the device's neighbours on the same site; the probability of every class SHALL be kept.

#### Scenario: Probabilities kept
- **WHEN** a candidate is classified
- **THEN** its record holds the probability of each of the four classes

### Requirement: Status from confidence
A candidate whose most probable class is *real event* or *sensor fault* with a probability at or above the configured confidence SHALL be **confirmed**; *expected pattern* at or above the confidence SHALL be **dismissed**; any other candidate SHALL be **to review**.

#### Scenario: Confident real event
- **WHEN** the decision model returns *real event* with probability 0.91 and the confidence is 0.8
- **THEN** the anomaly is confirmed as a real event, with 91 % shown

#### Scenario: Unsure
- **WHEN** the most probable class has probability 0.55, or is *cannot tell*
- **THEN** the anomaly is to review, and is shown separately from confirmed anomalies

#### Scenario: Expected pattern
- **WHEN** the decision model returns *expected pattern* with probability 0.9
- **THEN** the anomaly is dismissed: recorded, but not listed as an anomaly to act on by default

### Requirement: Explanation
Each confirmed anomaly SHALL have an explanation of at most four sentences, generated from the data used for its classification only, naming the device, the metric, the observed and usual values with their unit, and the time.

#### Scenario: Explanation content
- **WHEN** an anomaly is confirmed
- **THEN** its explanation mentions the device id, the metric, the values with units and the period, and no fact absent from the decision's data

### Requirement: Decision record
Every classified candidate SHALL be recorded with its device, site, metric, period, observed and usual values, kind of deviation, all class probabilities, the classification, the confidence threshold applied, the decision model deployment and version, the explanation (if any), and its review.

#### Scenario: Audit an anomaly
- **WHEN** an operator inspects an anomaly
- **THEN** they can see why it was flagged, every class probability, the threshold and model version used, and who reviewed it with which verdict

### Requirement: Anomaly API
The platform API SHALL let Readers list anomalies by site, device, status (confirmed, to review, dismissed, reviewed) and period (most recent first), and get a device's anomalies; and SHALL let Editors mark an anomaly as reviewed with a verdict (classification correct or incorrect) and an optional comment. Readers SHALL receive 403 when reviewing.

#### Scenario: Review
- **WHEN** an Editor marks a confirmed anomaly as reviewed with verdict "correct"
- **THEN** its status becomes reviewed, with the editor's name, the time and the verdict

#### Scenario: Reader cannot review
- **WHEN** a Reader tries to review an anomaly
- **THEN** the response is 403

### Requirement: Availability per environment
Anomaly detection SHALL be switchable per environment; when it is off, or when the decision model is not available, no detection runs, the portal hides the anomaly features and the rest of the platform is unaffected.

#### Scenario: Decision model unavailable
- **WHEN** the decision model cannot be deployed in an environment
- **THEN** provisioning reports it before deploying, and the environment can be provisioned with anomaly detection switched off

#### Scenario: Model failure during a cycle
- **WHEN** the decision model fails during a detection cycle
- **THEN** no candidate is classified without it, the failure is visible in the monitoring tools, and the next cycle retries the unclassified candidates

### Requirement: Detection observability
Each detection cycle SHALL be traced in Application Insights with the devices analysed, candidates found, decision model and LLM calls (with token usage) and anomalies recorded per status.

#### Scenario: Trace of a cycle
- **WHEN** a cycle finds one candidate and confirms it
- **THEN** Application Insights shows one trace with the cycle, the decision model call, the LLM call and the recorded anomaly
