# Spec Delta

## Purpose

Provides fleet-level summaries of a metric across devices and sites in a single request, so that comparisons and fleet-wide checks (by people or by the AI assistant) do not require one request per device.

## ADDED Requirements

### Requirement: Metric summary across the fleet
`GET /api/v1/fleet/metric-summary?metric=&from=&to=&groupBy=&siteId=` (Read) SHALL return, for one canonical metric over a range of at most 31 days, one entry per group (`groupBy` = `site` or `device`, optionally restricted to one site) with min, avg, max, sample count, number of devices, and the first and last values with their times. Groups without data for the metric SHALL be omitted.

#### Scenario: Compare humidity between sites
- **WHEN** a Reader requests the humidity summary for the last 7 days grouped by site
- **THEN** one entry per site with humidity data is returned with min, avg, max, sample and device counts

#### Scenario: Battery overview of a site
- **WHEN** a Reader requests the `battery_voltage` summary for the last 7 days grouped by device for site `lyon-01`
- **THEN** one entry per `lyon-01` device reporting battery voltage is returned with its first and last values, allowing the decline to be assessed

#### Scenario: Invalid metric
- **WHEN** the metric is not a canonical metric
- **THEN** the response is 400 listing valid metric names

### Requirement: Fleet status overview
`GET /api/v1/fleet/status?siteId=` (Read) SHALL return per site the number of devices, reporting devices, silent devices, and the ids of silent devices with their last-seen time (at most 100 per site).

#### Scenario: Silent devices of a site
- **WHEN** a Reader requests the fleet status for `lyon-01`
- **THEN** the counts are returned with the list of silent devices and their last-seen times

### Requirement: Fleet summary performance
Fleet summaries over 7 days for a fleet of 100 devices reporting every 30 seconds SHALL complete within 3 seconds at the 95th percentile, measured at the gateway.

#### Scenario: Seven-day summary latency
- **WHEN** 20 different 7-day site-level summaries are requested sequentially
- **THEN** the 95th percentile latency is under 3 seconds
