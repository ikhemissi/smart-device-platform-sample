#!/usr/bin/env bash
# Backfills realistic demo history for the devices of the current azd environment (M3 design D7): runs the db-migrator
# job once with the opt-in `backfill` task, then flushes the history cache so no cached chunk hides the new rows.
# Safe to re-run: devices already backfilled get nothing new.
#   scripts/backfill-history.sh                      # 30 days, every site
#   scripts/backfill-history.sh --days 7 --sites paris-01,lyon-01
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DAYS=30
SITES=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
    --sites) SITES="$2"; shift 2 ;;
    *) echo "Usage: $0 [--days N] [--sites site1,site2]" >&2; exit 2 ;;
  esac
done

eval "$(azd env get-values)"
export AZURE_RESOURCE_GROUP AZURE_CONTAINER_REGISTRY_NAME AZURE_CONTAINER_REGISTRY_ENDPOINT MIGRATOR_JOB_NAME MAINTENANCE_JOB_NAME
IMAGE="$("$ROOT/scripts/build-migrator-image.sh")"

# An execution override replaces the container definition: pass the job's own environment plus the backfill settings.
mapfile -t ENV < <(az containerapp job show -n "$MIGRATOR_JOB_NAME" -g "$AZURE_RESOURCE_GROUP" \
  --query "properties.template.containers[0].env[?name!='SDP_MIGRATOR_TASKS'].join('=', [name, value])" -o tsv)
ENV+=("SDP_MIGRATOR_TASKS=backfill" "SDP_BACKFILL_DAYS=$DAYS")
[[ -n "$SITES" ]] && ENV+=("SDP_BACKFILL_SITES=$SITES")

EXECUTION="$(az containerapp job start -n "$MIGRATOR_JOB_NAME" -g "$AZURE_RESOURCE_GROUP" \
  --container-name db-migrator --image "$IMAGE" --cpu 1 --memory 2Gi --env-vars "${ENV[@]}" --query name -o tsv)"
echo "History backfill started ($DAYS days, sites: ${SITES:-all}): $EXECUTION"
for _ in $(seq 1 180); do
  STATUS="$(az containerapp job execution show -n "$MIGRATOR_JOB_NAME" -g "$AZURE_RESOURCE_GROUP" \
            --job-execution-name "$EXECUTION" --query properties.status -o tsv)"
  case "$STATUS" in
    Succeeded) echo "History backfill succeeded"; break ;;
    Failed|Stopped|Degraded)
      echo "History backfill $STATUS. Logs: az containerapp job logs show -n $MIGRATOR_JOB_NAME -g $AZURE_RESOURCE_GROUP --execution $EXECUTION --container db-migrator" >&2
      exit 1 ;;
  esac
  sleep 10
done
[[ "${STATUS:-}" == "Succeeded" ]] || { echo "History backfill still running ($STATUS); check: az containerapp job execution show -n $MIGRATOR_JOB_NAME -g $AZURE_RESOURCE_GROUP --job-execution-name $EXECUTION" >&2; exit 1; }

# Chunks cached as complete before the backfill would hide the new rows.
if [[ -n "${REDIS_NAME:-}" ]]; then
  AZURE_EXTENSION_USE_DYNAMIC_INSTALL=yes_without_prompt az redisenterprise database flush \
    --cluster-name "$REDIS_NAME" -g "$AZURE_RESOURCE_GROUP" --only-show-errors -o none
  echo "History cache flushed ($REDIS_NAME)"
fi
