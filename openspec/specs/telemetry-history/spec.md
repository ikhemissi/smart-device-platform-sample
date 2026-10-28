# telemetry-history Specification

## Purpose

Lets clients read a device's measurement history — latest measurements, raw ranges and chart-ready aggregated series — quickly and with predictable freshness.

## Requirements

### Requirement: Latest measurements
`GET /api/v1/devices/{deviceId}/measurements/latest?count=N` SHALL return the device's N most recent measurements (default 20, maximum 100), most recent first, each with measurement time, payload format, metrics and attributes.

#### Scenario: Latest 20
- **WHEN** a Reader requests the latest measurements of a device with 500 stored measurements
- **THEN** the 20 most recent are returned in descending time order

#### Scenario: Unknown device
- **WHEN** the device does not exist
- **THEN** the response is 404

### Requirement: Measurements in a time range
`GET /api/v1/devices/{deviceId}/measurements?from=&to=` SHALL return the device's measurements with `from` ≤ time < `to`, in ascending time order, paged with an opaque cursor (page size default 500, maximum 1,000). `from` SHALL be before `to`, and the range SHALL NOT exceed 31 days.

#### Scenario: Paging through a range
- **WHEN** a range contains 1,200 measurements and pageSize is 500
- **THEN** three pages are returned via cursors with no duplicates or gaps

#### Scenario: Range too large
- **WHEN** the requested range spans 40 days
- **THEN** the response is 400 stating the 31-day maximum

### Requirement: Chart series with automatic aggregation
`GET /api/v1/devices/{deviceId}/series?metrics=&from=&to=&maxPoints=` SHALL return, per requested metric (1 to 4) with its unit, either raw points when the range holds at most `maxPoints` (default 500, max 2,000) measurements, or buckets with min/avg/max/count using the smallest of 1 min, 5 min, 15 min, 1 h, 6 h, 1 day producing at most `maxPoints` buckets. The response SHALL state the resolution.

#### Scenario: Short range returns raw points
- **WHEN** a 1-hour series is requested for a device reporting every 30 s
- **THEN** 120 raw points are returned with resolution `raw`

#### Scenario: Long range is aggregated
- **WHEN** a 30-day series is requested for a device reporting every 30 s with maxPoints 500
- **THEN** buckets of 6 h are returned (120 buckets), each with min, avg, max and count

#### Scenario: Metric not reported
- **WHEN** a series is requested for a metric the device never reported
- **THEN** that metric is returned with an empty list of points

### Requirement: Reported metrics
`GET /api/v1/devices/{deviceId}/metrics` SHALL return the canonical metrics the device has reported within the retention period, with unit and description.

#### Scenario: Ruuvi DF5 device metrics
- **WHEN** the metrics of a `ruuvi-df5` device are requested
- **THEN** temperature (°C), humidity (%), pressure (hPa), acceleration, battery voltage, TX power, movement counter and measurement sequence are listed

### Requirement: Freshness
History responses SHALL include every measurement persisted at least 60 seconds before the request, whether or not they are served from cache.

#### Scenario: New measurement appears
- **WHEN** a measurement is persisted and the same series is requested 60 seconds later
- **THEN** the measurement is included in the response

### Requirement: Caching and degradation
Repeated history requests SHALL be served from a shared cache when possible; if the cache is unavailable, requests SHALL still succeed from the database.

#### Scenario: Repeated 30-day series is faster
- **WHEN** the same 30-day series is requested twice within a minute
- **THEN** the second response is served from cache and is at least 3 times faster at the gateway

#### Scenario: Cache outage
- **WHEN** the cache is unreachable
- **THEN** history requests still succeed and a cache-error metric is emitted

### Requirement: History performance
An uncached 30-day series for one metric of a device reporting every 30 seconds SHALL complete within 1.5 seconds at the 95th percentile, measured at the gateway.

#### Scenario: Uncached 30-day series
- **WHEN** 50 different uncached 30-day series requests are made sequentially
- **THEN** the 95th percentile latency is under 1.5 s
