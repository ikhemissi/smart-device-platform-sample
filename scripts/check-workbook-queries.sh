#!/usr/bin/env bash
# Runs every query of the operations workbook (infra/modules/monitoring/queries/*.kql and the filter lists in
# parameters/*.kql) against the Application Insights of the current azd environment, for the last 24 hours and 7 days,
# the way the workbook runs them: the workbook's time range replaces --offset, and the {Site}, {DeviceType} and {DeviceId}
# filters are replaced by the given values (default: all). Fails on a query error, or on an empty result for queries
# that must return data while the simulator runs.
#   scripts/check-workbook-queries.sh [--site paris-01] [--device-type 'Ruuvi (DF5)'] [--device <id>] [--show key-indicators]
set -euo pipefail
cd "$(dirname "$0")/.."
SITE='*' DEVICE_TYPE='*' DEVICE='*' SHOW=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --site) SITE="$2"; shift 2 ;;
    --device-type) DEVICE_TYPE="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --show) SHOW="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
eval "$(azd env get-values)"
export AZURE_EXTENSION_USE_DYNAMIC_INSTALL=yes_without_prompt
APP="$(az resource list -g "$AZURE_RESOURCE_GROUP" --resource-type microsoft.insights/components --query '[0].name' -o tsv)"
# Queries that may legitimately be empty (no error or rejection in the period, or a filter matching nothing).
OPTIONAL="latest-errors rejections"

# A filter value as the workbook substitutes it: a quoted, comma-separated list.
quoted() {
  local IFS=, out=() v
  set -f  # '*' (all) must not expand to file names
  for v in $1; do out+=("'$v'"); done
  set +f
  echo "${out[*]}"
}
render() {
  sed -e "s/{Site}/$(quoted "$SITE")/g" -e "s/{DeviceType}/$(quoted "$DEVICE_TYPE")/g" -e "s/{DeviceId}/$(quoted "$DEVICE")/g" "$1"
}

echo "Filters: site=$SITE device-type=$DEVICE_TYPE device=$DEVICE"
status=0
for file in infra/modules/monitoring/parameters/*.kql infra/modules/monitoring/queries/*.kql; do
  name="$(basename "$file" .kql)"
  for offset in 24h 7d; do
    if ! result="$(az monitor app-insights query -g "$AZURE_RESOURCE_GROUP" --app "$APP" --offset "$offset" \
                    --analytics-query "$(render "$file")" --query 'tables[0].rows' -o json 2>/tmp/workbook-query.err)"; then
      echo "FAIL  $name ($offset): $(grep -v WARNING /tmp/workbook-query.err | head -3)"
      status=1
      continue
    fi
    rows="$(python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' <<<"$result")"
    if [[ "$rows" -eq 0 && " $OPTIONAL " != *" $name "* ]]; then
      echo "FAIL  $name ($offset): no rows"
      status=1
    else
      echo "ok    $name ($offset): $rows rows"
    fi
    if [[ "$name" == "$SHOW" && "$offset" == 24h ]]; then
      python3 -c 'import json,sys; [print("      " + " | ".join(str(c) for c in r)) for r in json.load(sys.stdin)]' <<<"$result"
    fi
  done
done
rm -f /tmp/workbook-query.err
exit "$status"
