# Demo guide — M5: threshold alerting

Shows how people are emailed when a monitored device's reading leaves its expected range, without being flooded, and how the alert history is kept ([PRD-005](../prd/PRD-005-m5-threshold-alerting.md), ADR-0009). Duration: about 25 minutes, mostly waiting for the simulator.

## Before the demo

The environment must have M1–M5 deployed and the simulator running:

```bash
azd env select sdp-dev
azd provision && azd deploy        # or: azd up
```

**About the values.** The simulated rooms report 13–23 °C, so the demo watches for **more than 28 °C** and the simulator injects **35 °C** for 5 minutes. In a real cold room the same threshold would be "between 2 and 8 °C".

**Email.** Use a mailbox you can open during the demo as the recipient. Emails come from `DoNotReply@<id>.azurecomm.net`, an Azure-managed domain. The first ones may land in the junk folder.

Requirements on the presenter's machine: `az`, `azd`, `curl`, Python 3. Useful variables:

```bash
eval "$(azd env get-values)"
TOKEN=$(az account get-access-token --scope "$API_SCOPE" --query accessToken -o tsv)   # Azure CLI is pre-authorized (dev)
SIMULATOR=$(az containerapp list -g "$AZURE_RESOURCE_GROUP" --query "[?tags.\"azd-service-name\"=='device-simulator'].name" -o tsv)
```

## 1. How alerting works

```
decoder ─▶ Service Bus topic "measurements" ─▶ subscription "alerting" (filter: monitored devices) ─▶ alert service ─▶ email
```

- The decoder publishes every stored measurement to a Service Bus topic, with the device, site and payload format as message properties.
- The `alerting` subscription only keeps the **monitored devices**. Its filter is configuration (`siteId = 'paris-01'` by default), not code:

  ```bash
  az servicebus topic subscription rule list -g "$AZURE_RESOURCE_GROUP" --namespace-name "$SERVICEBUS_NAMESPACE" \
    --topic-name measurements --subscription-name alerting --query "[].sqlFilter.sqlExpression" -o tsv
  ```

- The alert service compares each measurement with the device's thresholds, keeps one alert per breach, and emails the recipients.

## 2. Add a threshold

1. Open `$WEB_URL`, sign in as an Editor and open device `paris-01-ruuvi5-001` (Devices, site paris-01).
2. Below the latest measurements, **Alerts** is empty and **Thresholds** says *No threshold: this device never raises alerts*.
3. Choose **Add threshold**:
   - **Metric**: Air temperature (°C);
   - **Maximum**: `28` (leave the minimum empty);
   - **Email recipients**: your address.

   The dialog checks the values the same way as the API: try a minimum of 30 to see *The minimum must be lower than the maximum*, then clear it.
4. **Add threshold**. The table shows *at most 28 °C*, status **Watching**.
5. Do the same on `lyon-01-ruuvi5-001`. Site lyon-01 is **not monitored**, so this threshold never fires (step 5).

A Reader sees the same thresholds and alerts, without **Add threshold** or the edit and delete buttons. The API refuses their changes with 403.

## 3. Make the rooms too warm

The simulator injects anomalies from its configuration: here 35 °C for 5 minutes on both devices, starting 1 minute after it restarts. Updating the container app restarts the simulator (a new revision takes 1 to 2 minutes to start), and all devices reconnect.

```bash
az containerapp update -g "$AZURE_RESOURCE_GROUP" -n "$SIMULATOR" -o none \
  --set-env-vars "SDP_SIM_ANOMALIES=paris-01-ruuvi5-001:temperature=35:5m:1m,lyon-01-ruuvi5-001:temperature=35:5m:1m"
```

If the command reports a timeout, check whether the setting was applied before running it again:

```bash
az containerapp show -g "$AZURE_RESOURCE_GROUP" -n "$SIMULATOR" \
  --query "properties.template.containers[0].env[?name=='SDP_SIM_ANOMALIES'].value" -o tsv
```

## 4. The alert

About 3 minutes after the update:
- **Email**: *Alert: paris-01-ruuvi5-001 – Air temperature above 28 °C* arrives within 2 minutes of the first warm reading. In `sdp-dev` it was sent 6 to 20 seconds after the reading was received. It shows:
  - the device and site;
  - the value, worst value and threshold;
  - when the alert opened;
  - a link to the device page.
- **Device page** (refreshes every 30 seconds):
  - **Alerts** has an **Open** row highlighted in red: *above 28 °C, worst 35 °C*, emails *Alert sent*;
  - the threshold shows **Alerting**.
- **No flooding**: the device keeps reporting 35 °C every 30 seconds, but no other email arrives. A reminder would follow after one hour of continuous breach.
- **API** (as the AI assistant will use it in M6):

  ```bash
  curl -s -H "Authorization: Bearer $TOKEN" "$API_BASE_URL/alerts?status=open&siteId=paris-01" \
    | python3 -c "import sys,json; [print(a['deviceId'], a['metric'], a['direction'], a['worstValue'], a['openedAt']) for a in json.load(sys.stdin)]"
  ```

## 5. Only monitored devices alert

`lyon-01-ruuvi5-001` reports 35 °C too, over its 28 °C threshold, but nothing happens: no alert on its device page and no email. Its measurements are published like all others; the `alerting` subscription does not select site lyon-01.

## 6. Back to normal

When the 5 minutes are over, the next reading is back to around 20 °C:
- **Email**: *Back to normal: paris-01-ruuvi5-001 – Air temperature at or below 28 °C*, with the worst value and the times.
- **Device page**: the alert shows **Back to normal**, with its closing time and emails *Alert sent, back to normal sent*.

## 7. Change the monitored devices

Which devices are monitored is the platform operator's decision. Change it in configuration:

```bash
azd env set ALERTING_SUBSCRIPTION_FILTER "siteId IN ('paris-01','lyon-01')"
azd provision --preview     # the subscription's SQL filter (and removing the simulator's anomaly setting, if still set)
azd provision
```

Repeat the anomaly on lyon-01 only:

```bash
az containerapp update -g "$AZURE_RESOURCE_GROUP" -n "$SIMULATOR" -o none \
  --set-env-vars "SDP_SIM_ANOMALIES=lyon-01-ruuvi5-001:temperature=35:3m:1m"
```

About 3 minutes later an *Alert: lyon-01-ruuvi5-001…* email arrives; *Back to normal* follows 3 minutes after.

Return to the default filter (this also removes the simulator's anomaly setting):

```bash
azd env set ALERTING_SUBSCRIPTION_FILTER ""
azd provision
```

## 8. Behind the scenes

- **Reliable and idempotent.** Emails are queued in the same database transaction as the alert, then sent with retries for 30 minutes. A failure is shown as *Alert failed* in the alert history. A measurement delivered twice, or late, never sends a second email.
- **Disabling or deleting a threshold** closes its open alert without email; past alerts are kept.
- **Observability** (Application Insights, Logs):

  ```kusto
  customMetrics
  | where timestamp > ago(1h) and name startswith "sdp.alerts"
  | summarize total = sum(valueSum) by name, tostring(customDimensions["sdp.notification.kind"])
  ```

  Each evaluation (`sdp.alert.evaluate`, role `sdp-alert-service`) continues the trace of the device's message. Search for the alert's time in **Transaction search** to follow it from the decoder to the email.

## After the demo

If you stopped before step 7, remove the anomaly setting (a later restart of the simulator would replay it):

```bash
az containerapp update -g "$AZURE_RESOURCE_GROUP" -n "$SIMULATOR" -o none --remove-env-vars SDP_SIM_ANOMALIES
```

Delete the two thresholds on their device pages (or keep them for the next demo). Their alert history is kept.
