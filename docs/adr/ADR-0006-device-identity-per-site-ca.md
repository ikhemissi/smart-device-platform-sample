# ADR-0006 — Device identity: X.509 CA per site through DPS enrollment groups

- **Status:** Proposed
- **Date:** 2026-10-26

## Context

Devices must be onboarded without per-device manual registration and without symmetric keys, and trust must be manageable **per site** (adding/removing certificates per site, M4).

## Decision

1. **Each site owns a certificate authority**; device certificates are issued by their site's CA.
2. **The device certificate subject CN is the device ID** (= DPS registration ID = IoT Hub device ID) and is **prefixed with the site id** (e.g. `paris-01-ruuvi5-007`), making device IDs unique across sites.
3. **Each site CA is registered in Azure IoT Hub Device Provisioning Service (DPS) as a verified CA certificate, and DPS holds one X.509 enrollment group per site referencing it** (primary, and secondary for rotation). DPS only accepts device chains that end at a verified CA. The enrollment group assigns the device's site (device twin tag), which travels with every message ([ADR-0002](ADR-0002-ingestion-pipeline.md)).
4. **DPS is the source of truth for which certificates a site trusts**; the platform manages it through the DPS service API.

## Why hard to reverse

Certificates, their CN convention and the trust chain are burned into devices; changing the identity scheme means re-issuing credentials for the whole fleet. Site attribution of every stored measurement depends on the enrollment-group-per-site mapping.

## Consequences

- Site-level trust management with zero-touch onboarding and CA rotation (an enrollment group references a primary and a secondary CA).
- Registering a site CA requires proving trust in it: the platform marks it verified on upload (the uploader is trusted), instead of a proof-of-possession challenge.
- Removing a site certificate blocks new provisioning; devices already registered keep their IoT Hub identity until disabled — the platform disables a site's devices when its last certificate is removed.
- The DPS → IoT Hub link needs a shared access policy (exception in [ADR-0005](ADR-0005-passwordless-identity.md)).

## Alternatives considered

| Option | Why not |
|---|---|
| Individual enrollment per device | Per-device registration; does not match per-site management. |
| Certificate uploaded directly into the enrollment group, without a verified CA | Rejected by DPS at registration (`401002 Invalid certificate`): the device chain must end at a verified CA. |
| Proof-of-possession verification of each CA | Requires signing a challenge with the CA private key at upload time; "verified on upload" is sufficient when the uploader (Editor, deployment) is trusted. |
| Symmetric keys / SAS | Contradicts passwordless; keys extractable from devices. |
| Direct IoT Hub registration without DPS | No zero-touch onboarding, no per-site trust boundary. |
