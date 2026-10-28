# Demo guide — M3: device data and chart

Shows how a device's readings evolve: a chart over selectable periods, the latest measurements in detail, a page that follows the device live, and long periods that stay fast thanks to the cache ([PRD-003](../prd/PRD-003-m3-device-data-and-chart.md)). Duration: about 15 minutes.

## Before the demo

The environment must have M1–M3 deployed and the simulator running:

```bash
azd env select sdp-dev
azd provision && azd deploy        # or: azd up
```

**About the data.** Live measurements in `sdp-dev` start on 27 October 2026. The 30 days before that were generated once with the simulator's own device model (`scripts/backfill-history.sh`). Like all simulated readings, they're realistic but synthetic; 30-day charts are full from the start. A new environment needs the same one-off backfill; re-running it adds nothing.

Requirements on the presenter's machine: `az`, `azd`, `curl`, Python 3. Useful variables:

```bash
eval "$(azd env get-values)"
TOKEN=$(az account get-access-token --scope "$API_SCOPE" --query accessToken -o tsv)   # Azure CLI is pre-authorized (dev)
DEVICE=paris-01-ruuvi5-001
```

## 1. From the list to a device

1. Open `$WEB_URL` and sign in.
2. Click a device name, for example `paris-01-ruuvi5-001`. The page `/devices/paris-01-ruuvi5-001` shows:
   - the device's site, format, status and last seen time;
   - a chart of **Air temperature (°C)** over the last 24 hours;
   - its latest 20 measurements.

## 2. The chart

- **Periods:**
  - **1 h** draws every measurement.
  - **24 h** draws 5-minute averages, with a shaded band from the minimum to the maximum of each 5 minutes.
  - **7 d** and **30 d** use 15-minute and 1-hour averages. Hovering shows the average, minimum and maximum with units and the bucket size.

  The caption above the chart says which.
- **Several metrics:** in **Metrics**, add **Relative humidity (%)**. It gets its own axis on the right; up to four metrics can be shown.
- **Zoom:** scroll on the chart, or drag the slider below it.
- **Custom range:** choose **Custom**, pick two dates within the last 30 days, then **Apply**.
- **Shareable view:** the address bar holds the metrics and period (`?metrics=temperature,humidity&period=7d`); paste it into another tab to get the same view.

## 3. Live data

Keep the page open on **1 h**. The simulated device reports every 30 seconds:
- within a minute, a new row appears at the top of **Latest measurements**, briefly highlighted, and the chart extends;
- switch to another browser tab and back: refreshing pauses while the page is hidden.

## 4. The API behind the page

The page uses the same API the AI assistant will use in M6:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$API_BASE_URL/devices/$DEVICE/metrics" | python3 -m json.tool | head -20
curl -s -H "Authorization: Bearer $TOKEN" "$API_BASE_URL/devices/$DEVICE/measurements/latest?count=3" | python3 -m json.tool | head -30
FROM=$(date -u -d '-1 hour' +%FT%TZ)
curl -s -H "Authorization: Bearer $TOKEN" "$API_BASE_URL/devices/$DEVICE/series?metrics=temperature&from=$FROM" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['resolution'], len(d['series'][0]['points']), 'points')"
FROM=$(date -u -d '-30 days' +%FT%TZ)
curl -s -H "Authorization: Bearer $TOKEN" "$API_BASE_URL/devices/$DEVICE/series?metrics=temperature&from=$FROM&maxPoints=500" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); p=d['series'][0]['points']; print(d['resolution'], len(p), 'buckets,', sum(b['count'] for b in p), 'measurements')"
```

Expected output: `raw` with about 120 points for the last hour (one every 30 s), and `6h 121 buckets, about 86,400 measurements` for 30 days.

## 5. Long periods stay fast: the cache

Each 30-day series reads about 86,400 measurements. Completed hours and days are kept in Azure Managed Redis, so the same period requested again, or a moment later, is answered from memory. The current hour is cached for only 30 seconds, so new data shows up within a minute.

1. Clear the cache, then request the same 30-day series twice:

   ```bash
   AZURE_EXTENSION_USE_DYNAMIC_INSTALL=yes_without_prompt az redisenterprise database flush \
     --cluster-name "$REDIS_NAME" -g "$AZURE_RESOURCE_GROUP" --only-show-errors -o none
   for i in 1 2; do
     curl -s -o /dev/null -w "request $i: %{time_total}s\n" -H "Authorization: Bearer $TOKEN" \
       "$API_BASE_URL/devices/$DEVICE/series?metrics=pressure&from=$FROM&maxPoints=500"
   done
   ```

2. In **Application Insights → Logs** (wait a minute or two), compare the time spent in the gateway and in the device API for these two requests:

   ```kusto
   requests
   | where timestamp > ago(15m) and name has "/series"
   | project timestamp, component = cloud_RoleName, duration
   | order by timestamp desc
   | take 10
   ```

   Typical figures: about 300 ms the first time, 20–40 ms the second.

3. Show the cache hit ratio:

   ```kusto
   customMetrics
   | where timestamp > ago(1h) and name == "sdp.cache.lookups"
   | summarize keys = sum(valueSum) by outcome = tostring(customDimensions["sdp.cache.outcome"]), bin(timestamp, 5m)
   | render columnchart
   ```

   Cache problems never break the page: if Redis is unreachable, requests are answered from the database and `sdp.cache.errors` counts the failures.

## 6. Behind the scenes (optional)

The decoder records, per Event Hubs partition, the time up to which everything has been stored (the ingestion watermark). Only chunks older than that are cached for a day, so a processing backlog can never freeze incomplete data in the cache:

```bash
scripts/psql.sh -c "SELECT partition_id, watermark, now() - watermark AS behind FROM sdp.ingestion_watermarks ORDER BY 1"
```

Normally every partition is a few seconds behind real time.
