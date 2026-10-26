# PRD-001 — M1: Device data is stored

| Field | Value |
|---|---|
| Status | Draft |
| Milestone | M1 |
| Depends on | — |
| Overview | [PRD-000](PRD-000-product-overview.md) |

## 1. Goal

Demonstrate that measurements sent by devices of **different makes, each using its own compact binary format**, arrive on the platform, are translated into readable values and are **kept as history** — without anyone having to tell the platform which format a message uses.

## 2. Business context

Real sensor fleets mix vendors. The demonstrator uses message formats that real, commercially available sensors use today, so that the audience recognises a realistic situation:

| Format family | Typical devices | Typical readings |
|---|---|---|
| Ruuvi "data format 5" | RuuviTag environmental sensors (current generation) | Temperature, humidity, air pressure, acceleration, battery voltage, signal strength, movement counter |
| Ruuvi "data format 3" | RuuviTag (older firmware) | Temperature, humidity, air pressure, acceleration, battery voltage |
| BTHome v2 | Open-standard Bluetooth sensors (e.g. Shelly, custom firmware) | Temperature, humidity, pressure, battery, CO₂, illuminance… |
| Cayenne Low Power Payload | LoRaWAN sensors and many maker devices | Temperature, humidity, pressure, illuminance, analog inputs, GPS… |

Since no physical devices are available, a **device simulator** plays the role of a fleet spread across several sites.

## 3. User stories

| ID | As a… | I want… | So that… |
|---|---|---|---|
| M1-US1 | Demonstrator presenter | to start a simulated fleet of devices across several sites, with a mix of formats | I can show a realistic, heterogeneous fleet |
| M1-US2 | Demonstrator presenter | to choose how many devices, which sites and how often they report | I can adapt the demo to the audience |
| M1-US3 | Data Viewer (future) | every measurement to be translated into readable values with units | I can compare devices regardless of their make |
| M1-US4 | Data Viewer (future) | every measurement to be kept with the time it was received | I can look at history later |
| M1-US5 | Platform operator | a device to appear automatically in the platform the first time it reports | I do not have to register every device by hand |
| M1-US6 | Platform operator | unreadable or unknown messages to be set aside, not lost and not blocking others | I can investigate faulty devices without impacting the fleet |
| M1-US7 | Platform operator | only devices holding a certificate trusted for their site to be able to send data | untrusted devices cannot inject data |

## 4. Acceptance criteria

1. The simulator can run at least **50 devices across at least 3 sites**, mixing all four formats.
2. A measurement sent by a simulated device is stored, decoded, within **1 minute**.
3. Each stored measurement contains: the device, its site, the format it was sent in, the time it was received, the original message and the readable values with their units.
4. The same measurement delivered twice is stored **once**.
5. A message in an unknown or corrupted format is kept aside with the reason it could not be read; other messages keep flowing.
6. A device without a valid certificate for a known site cannot send data.
7. Translating the official sample messages published by each format's maintainer gives exactly the published values.
8. History is kept for at least **30 days**.

## 5. Out of scope

- Viewing data in a user interface (M2, M3).
- Managing site certificates from a user interface (M4) — the demo sites and their certificates are prepared automatically.
- Encrypted device formats (e.g. encrypted BTHome).
- Editing or deleting measurements.

## 6. Demo script (outline)

1. Deploy the platform; the demo sites and their certificates are prepared automatically.
2. Start the simulator with 3 sites × 10 devices.
3. Show measurements arriving with readable values, and follow the journey of one message from the device to its storage.
4. Inject a corrupted message; show it set aside with its reason while the rest keeps flowing.
