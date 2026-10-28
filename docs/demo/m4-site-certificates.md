# Demo guide — M4: site certificate management

Shows how an operator controls which devices may join the platform, site by site: trusting a site's CA certificate, rotating it without downtime, and what happens to the devices when a site's last certificate is removed ([PRD-004](../prd/PRD-004-m4-site-certificates.md), ADR-0006). Duration: about 20 minutes.

## Before the demo

The environment must have M1–M4 deployed and the simulator running:

```bash
azd env select sdp-dev
azd provision && azd deploy        # or: azd up
```

The rotation and removal steps use a **dedicated test site** with its own local simulator, so the demo sites (`paris-01`, `lyon-01`, `marseille-01`) and their history are never disturbed. Its id must start with `test-`, so it can be removed afterwards (step 8).

Requirements on the presenter's machine: `az`, `azd`, `curl`, `openssl`, Python 3, and Java 25 to run the simulator. Prepare the simulator and two CA certificates for the test site, as the site's own PKI would provide them (one CA in use, one to rotate to):

```bash
eval "$(azd env get-values)"
TOKEN=$(az account get-access-token --scope "$API_SCOPE" --query accessToken -o tsv)   # Azure CLI is pre-authorized (dev)
SITE=test-nice-01
./mvnw -q -pl services/device-simulator -am package -DskipTests

DEMO=$(mktemp -d)
for ca in ca-2026 ca-2027; do
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$DEMO/$ca.key"
  openssl req -x509 -new -key "$DEMO/$ca.key" -sha256 -days 365 -subj "/CN=$SITE $ca/O=Nice warehouse" \
    -addext "basicConstraints=critical,CA:true,pathlen:0" -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$DEMO/$ca.pem"
done
ls "$DEMO"     # ca-2026.pem and ca-2027.pem are uploaded; the keys stay with the site (here: the simulator)
```

## 1. Trust at a glance

1. Open `$WEB_URL`, sign in and choose **Sites**. Each site shows:
   - its devices;
   - how many certificates it trusts;
   - the next expiry, with a status badge (**Valid**, **Expiring** within 30 days, **Expired**).
2. Open `paris-01`. Its CA was created when the environment was deployed, so its **Added** column says *Deployment*.
   - **Recent changes** shows *Certificate found in provisioning*: the provisioning service recorded the CA it found in the Device Provisioning Service (DPS) when it first started.
   - DPS stays the source of truth: the service compares its records with DPS at start-up and every hour.
3. Signed in as a Reader (only `DeviceData.Read`), the same pages show no **Create site**, **Add certificate** or remove actions. The API also refuses the change with 403, both through API Management and directly on the service.

## 2. Create a site

1. On **Sites**, choose **Create site**. Enter the id `test-nice-01` and the name `Nice warehouse`, then **Create site**.
2. Open the new site: *This site trusts no certificate*, so no device can join it yet.

## 3. Trust the site's first CA

1. Choose **Add certificate** and select `ca-2026.pem` from `$DEMO`, then **Add certificate**.
   - It becomes the **primary** certificate, and **Recent changes** records who added it.
   - The service refuses:
     - files holding a private key, which are discarded and never logged;
     - certificates that are not a CA;
     - expired certificates;
     - a certificate the site already trusts.
2. Start the site's devices. Each one gets a device certificate issued by `ca-2026` and joins through DPS:

   ```bash
   cat "$DEMO/ca-2026.pem" "$DEMO/ca-2026.key" > "$DEMO/site-$SITE-ca.pem"
   SDP_DPS_ID_SCOPE=$DPS_ID_SCOPE SDP_SIM_CA_DIRECTORY=$DEMO SDP_SIM_SITES=$SITE SDP_SIM_DEVICES_PER_SITE=2 \
   SDP_SIM_INTERVAL=10s SDP_SIM_JITTER=1s \
     java -jar services/device-simulator/target/device-simulator-0.1.0-SNAPSHOT.jar
   ```

   Within seconds the log shows `Device test-nice-01-ruuvi5-001 provisioned`. Within a minute **Devices** lists both devices of site `test-nice-01`, reporting.

## 4. Rotate the CA without downtime

A site can trust two certificates at once, so a CA is replaced before it expires without stopping the devices:
1. **Add certificate** with `ca-2027.pem`: it becomes the **secondary** certificate. **Add certificate** is now disabled, since a site trusts at most two.
2. Remove `ca-2026` (the bin icon). The confirmation explains that devices issued by it can no longer join, while the other certificate keeps working. Confirm.
   - `ca-2027` is promoted to **primary**.
   - The devices keep sending, because devices already connected are not interrupted. Only joining or re-joining checks the CA.
3. Move the devices to the new CA, as the site would roll out new device certificates. Stop the simulator (Ctrl+C), then restart it with `ca-2027`:

   ```bash
   cat "$DEMO/ca-2027.pem" "$DEMO/ca-2027.key" > "$DEMO/site-$SITE-ca.pem"
   ```

   Then run the same `java -jar …` command as in step 3. The devices join again, now with certificates issued by `ca-2027`.

## 5. Remove the site's last certificate

1. Remove `ca-2027`. The confirmation changes: *This is the site's last certificate: all 2 devices of test-nice-01 will be disabled and stop sending data.* Confirm with **Remove and disable devices**.
2. The page reports **2 device(s) disabled** and *This site trusts no certificate … and its devices are disabled*.
   - The devices stop sending at once: their device page gets no new measurements, and in IoT Hub they're `disabled`.

     ```bash
     az iot hub device-identity list --hub-name "$IOTHUB_NAME" --auth-type login \
       --query "[?tags.siteId=='$SITE'].{device:deviceId, status:status}" -o table
     ```

   - After about 4 minutes the device client gives up reconnecting. The simulator logs `lost its connection, joining again`, then `cannot connect` every minute at most: DPS refuses a site that trusts no certificate.
   - Measurements already received are kept.
3. The site's enrollment group is disabled, not deleted. So the deployment scripts never restore trust an operator removed.

## 6. Restore trust

1. **Add certificate** with `ca-2027.pem` again. The page reports **2 device(s) re-enabled**.
   - Only devices the platform disabled are re-enabled; a device disabled by someone else stays disabled.
2. Within a minute the simulator logs `provisioned` again and measurements resume. In `sdp-dev` they resumed 15 to 40 seconds after the certificate was added, depending on where each device was in its retry back-off.

## 7. Audit and what happens behind the scenes

- **Recent changes** on the site page lists every step, newest first: who added or removed which certificate (subject, slot), and when the platform disabled or re-enabled devices. The same list is in the API:

  ```bash
  curl -s -H "Authorization: Bearer $TOKEN" "$API_BASE_URL/sites/$SITE/audit?limit=10" \
    | python3 -c "import sys,json; [print(e['occurredAt'][:19], e['action'], e['actorName']) for e in json.load(sys.stdin)]"
  ```

- **DPS** holds the trust: one enrollment group per site, referencing up to two verified CA certificates.

  ```bash
  az iot dps enrollment-group show --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" --enrollment-id "site-$SITE" \
    --auth-type login --query "{status:provisioningStatus, primary:attestation.x509.caReferences.primary, secondary:attestation.x509.caReferences.secondary}"
  ```

- **One API, two services.** API Management publishes one API: site trust operations go to the provisioning service, everything else to the device API. In Application Insights (Logs):

  ```kusto
  requests
  | where timestamp > ago(30m) and name has "/api/v1/"
  | summarize count() by cloud_RoleName, name
  ```

- **No secrets.** The provisioning service calls DPS and IoT Hub with its managed identity. Its roles are limited to enrollment groups, CA certificates and device status.

## 8. After the demo

Stop the simulator (Ctrl+C), then remove the test site: its DPS enrollment group and certificates, its IoT Hub devices and its database records. The API deliberately cannot delete a site; this script only accepts `test-*` and `e2e-*` sites.

```bash
scripts/remove-test-site.sh "$SITE"
rm -rf "$DEMO"
```
