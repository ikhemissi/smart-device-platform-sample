# PRD-003 — M3: Device data and chart

| Field | Value |
|---|---|
| Status | Draft |
| Milestone | M3 |
| Depends on | M2 |
| Overview | [PRD-000](PRD-000-product-overview.md) |

## 1. Goal

Let users open a device and **understand how its readings evolve over time**, and see its **latest 20 measurements** in detail.

## 2. User stories

| ID | As a… | I want… | So that… |
|---|---|---|---|
| M3-US1 | Data Viewer | to open a device from the list and see its details | I can focus on one device |
| M3-US2 | Data Viewer | to see a chart of a reading (e.g. temperature) over time | I understand its evolution |
| M3-US3 | Data Viewer | to choose which readings to chart among those the device provides | I compare relevant readings |
| M3-US4 | Data Viewer | to choose the period: last hour, 24 hours, 7 days, 30 days or a custom range | I zoom in or out |
| M3-US5 | Data Viewer | to see the latest 20 measurements in a table with readable values and units | I can check precise values |
| M3-US6 | Data Viewer | the page to refresh by itself while I watch | I see new measurements arrive during the demo |
| M3-US7 | Data Viewer | long periods to load fast | exploring history is pleasant |

## 3. Acceptance criteria

1. The device page shows the device information, a chart and the latest 20 measurements (most recent first).
2. The chart offers every reading the device has reported, with its unit; at least one reading is selected by default (temperature when available).
3. Period choices: 1 h, 24 h, 7 d, 30 d and custom (within the 30-day history). For long periods, the chart shows minimum, average and maximum per time interval so trends remain readable.
4. The latest-measurements table shows reception time and every reading with its unit.
5. While open, the page shows new measurements within **1 minute** of their arrival without a manual reload.
6. A chart for 30 days of a device reporting every 30 seconds displays in under **3 seconds**; displaying the same period again is noticeably faster.
7. A device without measurements shows an explicit empty state.

## 4. Out of scope

- Comparing several devices on the same chart.
- Exporting data (CSV, Excel).
- Editing measurements.
