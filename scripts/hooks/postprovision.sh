#!/usr/bin/env bash
# azd postprovision hook (M1 design D5/D8): migrate the database with the migrator job, build the anomaly detector
# image (M7), then prepare demo sites.
# Runs with the deployer's Entra identity; azd exports the Terraform outputs as environment variables.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
: "${AZURE_RESOURCE_GROUP:?}" "${AZURE_CONTAINER_REGISTRY_NAME:?}" "${AZURE_CONTAINER_REGISTRY_ENDPOINT:?}"
: "${MIGRATOR_JOB_NAME:?}" "${MAINTENANCE_JOB_NAME:?}"

# 1. Migrator image (rebuilt only when its sources change).
"$ROOT/scripts/build-migrator-image.sh" >/dev/null

# 2. Migrate (migrate + maintain) and wait for the outcome.
EXECUTION="$(az containerapp job start -n "$MIGRATOR_JOB_NAME" -g "$AZURE_RESOURCE_GROUP" --query name -o tsv)"
echo "Database migration started: $EXECUTION"
for _ in $(seq 1 120); do
  STATUS="$(az containerapp job execution show -n "$MIGRATOR_JOB_NAME" -g "$AZURE_RESOURCE_GROUP" \
            --job-execution-name "$EXECUTION" --query properties.status -o tsv)"
  case "$STATUS" in
    Succeeded) echo "Database migration succeeded"; break ;;
    Failed|Stopped|Degraded)
      echo "Database migration $STATUS. Logs: az containerapp job logs show -n $MIGRATOR_JOB_NAME -g $AZURE_RESOURCE_GROUP --execution $EXECUTION --container db-migrator" >&2
      exit 1 ;;
  esac
  sleep 10
done
[[ "${STATUS:-}" == "Succeeded" ]] || { echo "Database migration timed out ($STATUS)" >&2; exit 1; }

# 3. Anomaly detector image (M7), after the migration: its schema and database principal must exist first.
"$ROOT/scripts/build-anomaly-detector-image.sh" >/dev/null

# 4. Demo sites: CAs in Key Vault and DPS enrollment groups.
"$ROOT/scripts/bootstrap-demo-sites.sh"
