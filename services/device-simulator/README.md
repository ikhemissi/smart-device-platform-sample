# device-simulator

Simulates a fleet of sensors spread over several sites, sending **real device formats** (Ruuvi DF5/DF3, BTHome v2, Cayenne LPP) to IoT Hub. Each device authenticates with an X.509 certificate issued by its **site CA** and is provisioned through DPS (ADR-0006). Implements the `device-simulation` spec of OpenSpec change `m1-device-data-ingestion`.

## Behaviour

- **Fleet**: devices `<siteId>-<format>-<nnn>` (e.g. `paris-01-ruuvi5-007`), formats assigned round-robin per site from the format mix; identities are stable across restarts.
- **Identity**: at start, each device gets an in-memory EC P-256 certificate (CN = device id, client authentication) signed by its site CA, read from Key Vault secret `site-<siteId>-ca` (PEM bundle: CA certificate + private key) with the simulator's Entra identity. It registers with DPS (enrollment group of its site), then connects to the assigned IoT Hub over MQTT. Connection failures are retried with back-off (2 s → 60 s).
- **Losing trust**: when the IoT Hub client gives up reconnecting (for instance after its site's last certificate was removed, which disables its devices), the device joins again through DPS with the same back-off: DPS refuses it while its site trusts no certificate, and it resumes sending once a certificate is added again.
- **Readings**: per-device baselines (seeded by device id); temperature/humidity follow a daily cycle with noise, pressure drifts, batteries are computed from the time and device seed, counters increase; values stay within each format's valid range. Configured aging devices discharge to 2.5 V about every 16 days and restart with a new battery. Encoded with the same codec library as the decoder.
- **Messages**: binary body (`application/octet-stream`), a unique message id, and a W3C `traceparent` property from a `sdp.device.send` PRODUCER span, so each message can be followed into the decoder.
- **Faults and anomalies**: a share of messages can be corrupted (unknown leading byte `0x7F`, or truncated frame); anomalies force a metric of a device to a value for a time window.
- **Stop**: on `SIGTERM` (or after `SDP_SIM_DURATION`) all devices stop and connections close within 10 s.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `SDP_SIM_SITES` | `paris-01,lyon-01,marseille-01` | Sites (must have a CA and an enrollment group) |
| `SDP_SIM_DEVICES_PER_SITE` | `10` | Devices per site |
| `SDP_SIM_FORMAT_MIX` | `ruuvi-df5,ruuvi-df3,bthome-v2,cayenne-lpp` | Formats assigned round-robin; repeat one to weight it |
| `SDP_SIM_INTERVAL` / `SDP_SIM_JITTER` | `30s` / `5s` | Reporting interval and random jitter (±) |
| `SDP_SIM_DURATION` | — | Optional run duration (e.g. `15m`); runs until stopped otherwise |
| `SDP_SIM_CORRUPTION_RATE` | `0` | Share of corrupted messages, e.g. `0.01` |
| `SDP_SIM_ANOMALIES` | — | Comma-separated `<deviceId>:<metric>=<value>[~<ramp>]:<duration>[:<delay>]` |
| `SDP_SIM_AGING_DEVICES` | — | Comma-separated device ids whose battery drains to 2.5 V about every 16 days |
| `SDP_SIM_TRANSPORT` | `iothub` | `iothub` (DPS + IoT Hub) or `console` (dry run, logs payloads) |
| `SDP_DPS_ID_SCOPE` | — | DPS ID scope (required for `iothub`) |
| `SDP_DPS_GLOBAL_ENDPOINT` | `global.azure-devices-provisioning.net` | DPS global endpoint |
| `SDP_KEYVAULT_URI` | — | Key Vault holding `site-<siteId>-ca` bundles |
| `SDP_SIM_CA_DIRECTORY` | — | Offline alternative: directory with `site-<siteId>-ca.pem` files |
| `AZURE_CLIENT_ID` | — | Client id of the simulator's managed identity (in Azure) |

## Running

Dry run without Azure (payloads are logged as hex):

```bash
./mvnw -q -pl services/device-simulator -am package -DskipTests
SDP_SIM_TRANSPORT=console SDP_SIM_SITES=paris-01 SDP_SIM_DEVICES_PER_SITE=4 SDP_SIM_INTERVAL=2s SDP_SIM_JITTER=500ms \
SDP_SIM_DURATION=10s SDP_SIM_CORRUPTION_RATE=0.1 \
  java -jar services/device-simulator/target/device-simulator-0.1.0-SNAPSHOT.jar
```

Against a deployed environment from your machine (your Entra identity needs *Key Vault Secrets User* on the vault):

```bash
az login
SDP_DPS_ID_SCOPE=$(azd env get-value DPS_ID_SCOPE) SDP_KEYVAULT_URI=$(azd env get-value KEY_VAULT_URI) \
SDP_SIM_DURATION=15m \
  java -jar services/device-simulator/target/device-simulator-0.1.0-SNAPSHOT.jar
```

In Azure the simulator runs as a container app (one replica, no ingress) configured through the same variables.

### Anomalies on demand during a demo

Anomalies are configuration: set them on the container app, which starts a new revision (the anomaly window starts when the simulator starts, plus the optional delay):

```bash
az containerapp update -g <resource-group> -n <simulator-app> \
  --set-env-vars "SDP_SIM_ANOMALIES=paris-01-ruuvi5-001:temperature=12:5m"
# back to normal
az containerapp update -g <resource-group> -n <simulator-app> --remove-env-vars SDP_SIM_ANOMALIES
```

| Form | Readings | Looks like |
|---|---|---|
| `temperature=35:10m` | jump to 35 at once, held almost constant | a faulty sensor |
| `temperature=35~5m:10m` | rise to 35 over 5 minutes with the usual noise, held 10 minutes, back over 5 minutes | a room really warming up |

The anomaly detector (M7) classifies the first form on one device as a sensor fault, and the second form on several
devices of a site at once as a real event.

## Tests

`./mvnw -pl services/device-simulator test`: fleet planning, reading continuity and daily cycle, two days of payloads per format decoded by the platform codecs, anomalies, corruption rate and rejection, device certificates chaining to the site CA (EC/RSA CAs, PKCS#8 and OpenSSL traditional keys), IoT Hub message mapping, unique message ids with trace context, graceful stop within 10 s, run duration and connection retries.
