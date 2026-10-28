#!/usr/bin/env bash
# Runs every query of the operations workbook (infra/modules/monitoring/queries/*.kql) against the Application
# Insights of the current azd environment, for the last 24 hours and 7 days, the way the workbook runs them (the
# workbook's time range replaces --offset). Fails on a query error, or on an empty result for queries that must
# return data while the simulator runs.
#   scripts/check-workbook-queries.sh
set -euo pipefail
cd "$(dirname "$0")/.."
eval "$(azd env get-values)"
export AZURE_EXTENSION_USE_DYNAMIC_INSTALL=yes_without_prompt
APP="$(az resource list -g "$AZURE_RESOURCE_GROUP" --resource-type microsoft.insights/components --query '[0].name' -o tsv)"
# Queries that may legitimately be empty (no error or rejection in the period).
OPTIONAL="latest-errors rejections"

status=0
for file in infra/modules/monitoring/queries/*.kql; do
  name="$(basename "$file" .kql)"
  for offset in 24h 7d; do
    if ! rows="$(az monitor app-insights query -g "$AZURE_RESOURCE_GROUP" --app "$APP" --offset "$offset" \
                  --analytics-query "$(cat "$file")" --query 'length(tables[0].rows)' -o tsv 2>/tmp/workbook-query.err)"; then
      echo "FAIL  $name ($offset): $(grep -v WARNING /tmp/workbook-query.err | head -3)"
      status=1
      continue
    fi
    if [[ "$rows" -eq 0 && " $OPTIONAL " != *" $name "* ]]; then
      echo "FAIL  $name ($offset): no rows"
      status=1
    else
      echo "ok    $name ($offset): $rows rows"
    fi
  done
done
rm -f /tmp/workbook-query.err
exit "$status"
