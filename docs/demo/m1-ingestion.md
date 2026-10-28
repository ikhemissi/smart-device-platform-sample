# Demo guide — M1: device data is stored

Shows that measurements from devices of different makes, each using its own binary format, reach the platform, are decoded without being told the format, and are kept as history ([PRD-001](../prd/PRD-001-m1-device-data-ingestion.md) §6). Duration: about 15 minutes, plus deployment.

## Before the demo

Deploy (about 30 minutes; see the [README](../../README.md#deploy-to-azure)) or reuse a running environment:

```bash
azd env select sdp-dev            # or: azd env new <name> --location francecentral && azd up
azd env get-values | grep -E 'AZURE_RESOURCE_GROUP|SIMULATOR_APP_NAME|DPS_NAME|POSTGRES_HOST'
```

Requirements on the presenter's machine: `az` (with the `azure-iot` extension), `azd`, Python 3, and `psql` or Podman/Docker (used by `scripts/psql.sh`). The presenter is an Entra administrator of the database (dev environments).

Useful variables for the commands below:

```bash
RG=$(azd env get-value AZURE_RESOURCE_GROUP)
SIM=$(azd env get-value SIMULATOR_APP_NAME)
DPS=$(azd env get-value DPS_NAME)
```

## 1. The platform and its sites are ready

Deployment prepared three demo sites, each with its own certificate authority. Only devices holding a certificate issued by one of these authorities can join.

```bash
az iot dps enrollment-group list --dps-name "$DPS" -g "$RG" --auth-type login \
  --query "[].{site:enrollmentGroupId, ca:attestation.x509.caReferences.primary, enabled:provisioningStatus}" -o table
```

## 2. A fleet of 30 devices across 3 sites, mixing 4 real formats

The simulator runs 10 devices per site, using Ruuvi data formats 5 and 3, BTHome v2 and Cayenne LPP, each reporting every 30 seconds. Only settings that differ from these defaults appear in its configuration:

```bash
az containerapp show -n "$SIM" -g "$RG" \
  --query "properties.template.containers[0].env[?starts_with(name,'SDP_SIM')||starts_with(name,'SDP_DPS')].{name:name,value:value}" -o table
```

To adapt the fleet to the audience (new revision, devices restart within a minute), e.g. 5 devices per site every 10 seconds:

```bash
az containerapp update -n "$SIM" -g "$RG" --set-env-vars SDP_SIM_DEVICES_PER_SITE=5 SDP_SIM_INTERVAL=10s -o none
```

> Settings changed this way are reset by the next `azd provision`/`azd up`. Permanent changes go to the Terraform variable `simulator_settings`.

## 3. Measurements arrive, readable and with their history

Latest measurements — every format decoded into the same readable values:

```bash
scripts/psql.sh -c "
SELECT measured_at::time(0) AS at, device_id, payload_format AS format,
       metrics->>'temperature' AS temperature_c, metrics->>'humidity' AS humidity_pct, metrics->>'pressure' AS pressure_hpa
  FROM sdp.measurements ORDER BY measured_at DESC LIMIT 12;"
```

Devices registered automatically on their first message, per site:

```bash
scripts/psql.sh -c "
SELECT site_id, count(*) AS devices, max(last_seen_at)::time(0) AS last_seen,
       string_agg(DISTINCT last_payload_format, ', ') AS formats
  FROM sdp.devices GROUP BY site_id ORDER BY site_id;"
```

The original binary message is kept next to the decoded values:

```bash
scripts/psql.sh -c "
SELECT device_id, payload_format, encode(raw_payload, 'hex') AS raw, metrics
  FROM sdp.measurements WHERE device_id = 'paris-01-ruuvi5-001' ORDER BY measured_at DESC LIMIT 1;"
```

Time from arrival on the platform to storage (target: under 1 minute):

```bash
scripts/psql.sh -c "
SELECT round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM received_at - measured_at))::numeric, 1) AS p50_s,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM received_at - measured_at))::numeric, 1) AS p99_s
  FROM sdp.measurements WHERE measured_at > now() - interval '15 minutes';"
```

## 4. Follow one message from the device to its storage

Every message carries a trace context from the device. The journey of a device's latest message (telemetry reaches Application Insights within a few minutes):

```bash
scripts/trace-device.sh paris-01-ruuvi5-001
```

The output lists the device's send, the decoder receiving the batch (linked to the send), the decoding of that message (site, format, outcome), the database writes and the checkpoint. In the portal, the same journey is visible in Application Insights → *Transaction search* (search for the device id) and the *Application map* shows `sdp-device-simulator`, `sdp-telemetry-decoder`, Event Hubs, Blob Storage and PostgreSQL.

For the whole platform at a glance, open the operations workbook (`azd env get-value OPERATIONS_WORKBOOK_URL`): devices reporting, measurements stored per 15 minutes and ingestion latency at the top; under *Critical components*, the IoT Hub telemetry, Event Hubs incoming messages and PostgreSQL charts follow the same curve.

## 5. A corrupted message is set aside, the rest keeps flowing

Make 5% of the messages unreadable (unknown leading byte or truncated frame):

```bash
az containerapp update -n "$SIM" -g "$RG" --set-env-vars SDP_SIM_CORRUPTION_RATE=0.05 -o none
```

After a minute, rejected messages appear with the reason they could not be read, while measurements keep arriving:

```bash
scripts/psql.sh -c "
SELECT received_at::time(0) AS at, device_id, reason, detail, encode(raw_payload, 'hex') AS raw
  FROM sdp.rejected_messages ORDER BY received_at DESC LIMIT 8;"
scripts/psql.sh -c "
SELECT (SELECT count(*) FROM sdp.measurements WHERE measured_at > now() - interval '2 minutes') AS stored_2min,
       (SELECT count(*) FROM sdp.rejected_messages WHERE received_at > now() - interval '2 minutes') AS rejected_2min;"
```

In the operations workbook, *Messages rejected* rises and *Device messages rejected per hour, by reason* shows the two reasons.

Back to normal:

```bash
az containerapp update -n "$SIM" -g "$RG" --set-env-vars SDP_SIM_CORRUPTION_RATE=0 -o none
```

## 6. Only trusted devices can join (optional)

A device whose certificate comes from an unknown authority is refused by the provisioning service. From the presenter's machine (JDK 25), with a throwaway authority:

```bash
W=$(mktemp -d)
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$W/k.pem"
openssl req -x509 -new -key "$W/k.pem" -days 1 -subj "/CN=rogue CA" \
  -addext "basicConstraints=critical,CA:true" -addext "keyUsage=critical,keyCertSign" -out "$W/c.pem"
cat "$W/c.pem" "$W/k.pem" > "$W/site-rogue-01-ca.pem"
./mvnw -q -pl services/device-simulator -am package -DskipTests
SDP_DPS_ID_SCOPE=$(azd env get-value DPS_ID_SCOPE) SDP_SIM_CA_DIRECTORY="$W" SDP_SIM_SITES=rogue-01 \
SDP_SIM_DEVICES_PER_SITE=1 SDP_SIM_DURATION=20s \
  java -jar services/device-simulator/target/device-simulator-0.1.0-SNAPSHOT.jar 2>&1 | grep -o "Service error: [^-]*- [^-]*"
rm -rf "$W"
```

Expected: `Service error: 401002 - CA certificate not found` — the device never reaches IoT Hub.

## After the demo

```bash
azd down --purge      # removes every resource of the environment (about 15 minutes)
```
