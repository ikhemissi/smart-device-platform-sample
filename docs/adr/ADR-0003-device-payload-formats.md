# ADR-0003 — Device payload formats and leading-byte format detection

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

The decoder must handle **real-world** device formats and determine the format **from the first byte(s) of the payload**, without out-of-band metadata.

## Decision

1. Payloads are the device's **native binary frame**, sent as the message body. The **first byte selects the format**:

   | Leading byte | Format | Rule |
   |---|---|---|
   | `0x05` | Ruuvi Data Format 5 (RAWv2) | Native frame; first byte is Ruuvi's own format byte |
   | `0x03` | Ruuvi Data Format 3 (RAWv1) | Native frame; first byte is Ruuvi's own format byte |
   | `0x40`, `0x44` | BTHome v2, unencrypted | Native frame from the BTHome device-information byte (service UUID stripped) |
   | `0xCA` | Cayenne LPP | One **format-marker byte** prepended to the standard LPP frame, because LPP has no self-identifying header |
   | anything else | — | Rejected as unknown format |

2. Self-identifying formats are sent unmodified; formats without a header get a marker byte from a reserved range that does not collide with self-identifying formats. Encrypted variants are not supported.
3. Decoded values are normalised into **canonical metric names with fixed units** (e.g. `temperature` °C, `pressure` hPa); this catalogue is a platform-wide contract.

## Why hard to reverse

The leading-byte registry and the Cayenne marker are a wire contract with every device (or gateway) in the field; changing them requires firmware/gateway updates. Canonical metric names and units are stored in history and used by APIs, the UI, alert thresholds and the AI agent; renaming or changing a unit requires a data migration and coordinated changes everywhere.

## Consequences

- Real Ruuvi/BTHome frames can be forwarded verbatim; detection is stateless and needs no device lookup.
- The 1-byte registry is a limited space; collisions must be checked before registering a new format.
- Cayenne LPP senders must prepend the marker — a small, documented deviation from pure LPP.

## Alternatives considered

| Option | Why not |
|---|---|
| Format given by message property / content type | Explicitly not wanted; lost through third-party gateways. |
| Format looked up from a device registry | Couples decoding to device metadata; not "from the first bytes". |
| Custom envelope for every format | Native Ruuvi/BTHome frames could no longer be forwarded unchanged. |
| Invented demo format | Real formats are required. |
