# PRD-000 — Smart Device Platform: Product Overview

| Field | Value |
|---|---|
| Status | Draft |
| Last updated | 2026-10-26 |
| Scope | Whole product (demonstrator) |

## 1. Vision

Show, end to end, how an organisation operating sensors across several **sites** can:

1. receive measurements from many devices that speak **different, compact binary formats**,
2. turn them into readable, comparable data and keep their **history**,
3. let people **browse devices and explore measurements** visually,
4. **control which devices may join** the platform, site by site,
5. be **notified by email** when a measurement crosses a threshold,
6. **ask questions about the data in natural language** to an AI assistant.

The product is a **demonstrator**: it must be convincing in front of customers and partners, easy to deploy from scratch in a fresh environment, and representative of production-grade practices, without aiming for production scale or SLAs.

## 2. Problem statement

Sensor fleets are heterogeneous: devices from different vendors encode their readings in different binary formats, and organisations struggle to (a) bring them into one consistent view, (b) control which physical devices are trusted, and (c) turn raw readings into insight and timely action. Teams evaluating such a platform need a tangible reference they can see working.

## 3. Personas

| Persona | Description | Main goals |
|---|---|---|
| **Data Viewer** (e.g. analyst, site manager) | Consults devices and their measurements | Find a device quickly, understand how its readings evolve, ask questions about trends |
| **Data Editor** (e.g. platform operator) | Everything a Viewer does, plus maintains reference data | Keep device information accurate, control which devices can join per site, define alert thresholds |
| **Alert recipient** (e.g. on-call technician) | Receives notifications, may not use the portal | Be told quickly and clearly when a device is out of its expected range |
| **Demonstrator presenter** | Runs the demo | Deploy the platform and simulated devices on demand, show each capability live |

## 4. Access model

People sign in with their organisational account. Two permission levels exist:

| Permission | Who | Allows |
|---|---|---|
| **Query device data** | Data Viewer | View devices, measurements, charts, sites, alerts; use the AI assistant |
| **Edit device data** | Data Editor | Everything above, plus edit device information, manage sites and site certificates, manage alert thresholds |

A person without either permission cannot see any data.

## 5. Business capabilities

| # | Capability | Delivered in |
|---|---|---|
| C1 | Ingest measurements from devices using several real-world binary formats and keep their history | M1 |
| C2 | Simulate a fleet of devices across several sites, on demand | M1 |
| C3 | Sign in and browse the list of devices | M2 |
| C4 | Explore a device's measurements: evolution chart and latest 20 measurements | M3 |
| C5 | Manage which devices may join, per site, through site certificates | M4 |
| C6 | Email alerts when a measurement crosses a threshold, for a selected subset of devices | M5 |
| C7 | Analyse devices and measurements with an AI assistant | M6 |

## 6. Milestones

| Milestone | Outcome | PRD |
|---|---|---|
| **M1 — Device data is stored** | Simulated devices send measurements; they are decoded and stored with their history | [PRD-001](PRD-001-m1-device-data-ingestion.md) |
| **M2 — Device list in the web portal** | Users sign in and browse the devices known to the platform | [PRD-002](PRD-002-m2-device-list.md) |
| **M3 — Device data and chart** | Users see how a device's readings evolve and its latest 20 measurements | [PRD-003](PRD-003-m3-device-data-and-chart.md) |
| **M4 — Site certificate management** | Editors control, per site, which certificates allow devices to join | [PRD-004](PRD-004-m4-site-certificates.md) |
| **M5 — Threshold alerting** | Selected devices trigger email alerts when out of range | [PRD-005](PRD-005-m5-threshold-alerting.md) |
| **M6 — AI analyst** | Users ask questions about devices and measurements in natural language | [PRD-006](PRD-006-m6-ai-analyst.md) |

Each milestone is demonstrable on its own and builds on the previous ones.

## 7. Success criteria (demonstrator)

- A presenter can stand up the full platform in an empty environment with a single command and tear it down afterwards.
- Within 1 minute of a simulated device sending a measurement, the measurement is visible in the portal.
- Every user action and data flow can be followed end to end in the monitoring tooling (useful during the demo to "show the plumbing").
- No passwords, keys or other shared secrets are handled by people or stored in configuration.

## 8. Out of scope (whole product)

- Production-grade scale, availability targets, disaster recovery and multi-region.
- Real physical devices and field gateways (simulated devices only; real devices must remain possible).
- Sending commands or firmware updates to devices.
- Billing, multi-tenancy, self-service user management (permissions are granted by an administrator).
- Mobile applications.

## 9. Assumptions

- "Editing device data" means editing **device information** (display name, description, location), **sites and their certificates**, and **alert thresholds**; recorded measurements are never edited.
- A **site** is a physical location grouping devices; each device belongs to exactly one site, determined by the site certificate it joined with.
- The demonstrator is deployed in Europe; all data stays in Europe.
