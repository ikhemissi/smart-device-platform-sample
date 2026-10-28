# telemetry-visualization Specification

## Purpose

Gives portal users a device page that shows how readings evolve over time and the latest measurements in detail, refreshing itself while open.

## Requirements

### Requirement: Device page
Selecting a device in the device list SHALL open a device page (addressable URL) showing the device information and status, a chart and the latest 20 measurements.

#### Scenario: Open from list
- **WHEN** a user selects device `paris-01-ruuvi5-001` in the list
- **THEN** the page `/devices/paris-01-ruuvi5-001` shows its information, chart and latest measurements

### Requirement: Metric selection
The chart SHALL offer the metrics the device reports, labelled with unit; up to 4 metrics can be shown at once; temperature SHALL be pre-selected when available, otherwise the first metric. Metrics with different units SHALL use separate value axes.

#### Scenario: Default selection
- **WHEN** the page opens for a device reporting temperature and humidity
- **THEN** temperature (°C) is charted by default and humidity (%) can be added on a second axis

### Requirement: Period selection
The user SHALL be able to choose 1 h, 24 h, 7 d, 30 d or a custom range within the retention period; the default is 24 h. Selected metrics and period SHALL be reflected in the URL.

#### Scenario: Switch to 7 days
- **WHEN** the user selects 7 d
- **THEN** the chart shows the last 7 days and the URL contains the period

### Requirement: Aggregated display
When the series is aggregated, the chart SHALL draw the average as a line and the min–max range as a shaded band, and the tooltip SHALL show min, avg, max and the bucket interval with units.

#### Scenario: 30-day view
- **WHEN** a 30-day period is displayed
- **THEN** the chart shows an average line with a min–max band and the tooltip states the bucket size

### Requirement: Latest measurements table
The page SHALL show the latest 20 measurements, most recent first, with measurement time and one column per reported metric including its unit, and the payload format.

#### Scenario: Table content
- **WHEN** the page opens for a device with measurements
- **THEN** 20 rows are displayed in descending time order with units in column headers

### Requirement: Auto-refresh
While the page is visible, the chart (for periods ending "now") and the table SHALL refresh at least every 30 seconds without a manual reload; refresh SHALL pause while the browser tab is hidden. New table rows SHALL be briefly highlighted.

#### Scenario: New measurement while watching
- **WHEN** the device sends a new measurement while the page is open
- **THEN** it appears in the table and chart within 60 seconds

### Requirement: Chart display performance
In a supported desktop browser, a 30-day chart for a device reporting every 30 seconds SHALL be fully rendered within 3 seconds of selecting the period, and noticeably faster when displayed again.

#### Scenario: Thirty-day chart
- **WHEN** a user selects 30 d on such a device for the first time
- **THEN** the chart is fully rendered within 3 seconds, and within 1 second when selected again

### Requirement: Empty and error states
The page SHALL show an explicit empty state when the device has no measurement in the selected period and a retry option when loading fails.

#### Scenario: No data in period
- **WHEN** the selected period contains no measurement
- **THEN** the chart area states that no data exists for this period and suggests a longer period
