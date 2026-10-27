#!/usr/bin/env bash
# Shows the journey of the latest message of a device, from the device's send to its storage, using the telemetry
# in Application Insights (Log Analytics workspace of the current azd environment).
#
#   scripts/trace-device.sh paris-01-ruuvi5-001 [lookback, default 30m]
#
# Telemetry is ingested with a delay of a few minutes: the latest message shown can be a few minutes old.
set -euo pipefail

DEVICE="${1:?usage: $0 <deviceId> [lookback e.g. 30m]}"
LOOKBACK="${2:-30m}"
RG="$(azd env get-value AZURE_RESOURCE_GROUP 2>/dev/null)"
WORKSPACE_ID="$(az monitor log-analytics workspace list -g "$RG" --query '[0].customerId' -o tsv)"

read -r -d '' QUERY <<KQL || true
let device = '${DEVICE}';
// The latest send of the device that has already been processed (the process span links to the send span).
let sends = materialize(AppDependencies
    | where TimeGenerated > ago(${LOOKBACK}) and Name == 'sdp.device.send' and tostring(Properties['sdp.device.id']) == device
    | project sendTime = TimeGenerated, sendOp = OperationId, sendMs = DurationMs);
let processes = materialize(AppRequests
    | where TimeGenerated > ago(${LOOKBACK}) and Name == 'process device-telemetry'
    | mv-expand link = todynamic(tostring(Properties['_MS.links']))
    | project processTime = TimeGenerated, processOp = OperationId, sendOp = tostring(link.operation_Id));
let chain = materialize(sends | join kind=inner processes on sendOp | top 1 by sendTime desc);
let processOp = toscalar(chain | project processOp);
union
    (chain | project step = '1', at = sendTime, role = 'sdp-device-simulator', span = 'sdp.device.send (device sends the message)', detail = strcat(device, ', ', round(sendMs, 0), ' ms')),
    (chain | project step = '2', at = processTime, role = 'sdp-telemetry-decoder', span = 'process device-telemetry (batch received, linked to the send)', detail = strcat('trace ', processOp)),
    (AppDependencies
        | where TimeGenerated > ago(${LOOKBACK}) and OperationId == processOp
        | where Name in ('sdp.ingest.batch', 'INSERT sdp.ingested_messages', 'INSERT sdp.measurements', 'INSERT sdp.rejected_messages', 'INSERT sdp.devices', 'checkpoint device-telemetry')
            or (Name == 'sdp.ingest.message' and tostring(Properties['sdp.device.id']) == device)
        | project step = '3', at = TimeGenerated, role = AppRoleName, span = Name,
                  detail = iff(Name == 'sdp.ingest.message', strcat(tostring(Properties['sdp.site.id']), ', ', tostring(Properties['sdp.payload.format']), ', ', tostring(Properties['sdp.ingestion.outcome'])), strcat(round(DurationMs, 1), ' ms')))
| order by step asc, at asc
| project at, role, span, detail
KQL

RESULT="$(az monitor log-analytics query -w "$WORKSPACE_ID" --analytics-query "$QUERY" -o json)"
if [[ "$RESULT" == "[]" ]]; then
  echo "No processed message found for $DEVICE in the last $LOOKBACK (telemetry can take a few minutes to arrive)."
  exit 1
fi
python3 - "$RESULT" <<'PY'
import json, sys
for r in json.loads(sys.argv[1]):
    print(f"{r['at'][11:23]}  {r['role']:<24} {r['span']:<62} {r['detail']}")
PY
