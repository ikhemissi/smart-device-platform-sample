#!/usr/bin/env bash
# azd postprovision hook (M1 design D5/D8): migrate the database with the migrator job, then prepare demo sites.
# Runs with the deployer's Entra identity; azd exports the Terraform outputs as environment variables.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
: "${AZURE_RESOURCE_GROUP:?}" "${AZURE_CONTAINER_REGISTRY_NAME:?}" "${AZURE_CONTAINER_REGISTRY_ENDPOINT:?}"
: "${MIGRATOR_JOB_NAME:?}" "${MAINTENANCE_JOB_NAME:?}"

# 1. Migrator image, tagged with a hash of its sources so unchanged code is not rebuilt.
TAG="$(cd "$ROOT" && find pom.xml services/Dockerfile libs/payload-codecs libs/db-schema services/db-migrator \
        -type f -not -path '*/target/*' -print0 | sort -z | xargs -0 sha1sum | sha1sum | cut -c1-12)"
IMAGE="$AZURE_CONTAINER_REGISTRY_ENDPOINT/sdp/db-migrator:$TAG"
if az acr repository show -n "$AZURE_CONTAINER_REGISTRY_NAME" --image "sdp/db-migrator:$TAG" -o none 2>/dev/null; then
  echo "Migrator image $TAG already built"
else
  echo "Building migrator image $TAG in ACR..."
  az acr build -r "$AZURE_CONTAINER_REGISTRY_NAME" -t "sdp/db-migrator:$TAG" -f "$ROOT/services/Dockerfile" \
    --build-arg SERVICE=db-migrator --no-logs "$ROOT" -o none
fi
for job in "$MIGRATOR_JOB_NAME" "$MAINTENANCE_JOB_NAME"; do
  az containerapp job update -n "$job" -g "$AZURE_RESOURCE_GROUP" --image "$IMAGE" -o none
done

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

# 3. Demo sites: CAs in Key Vault and DPS enrollment groups.
"$ROOT/scripts/bootstrap-demo-sites.sh"
