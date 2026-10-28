#!/usr/bin/env bash
# Builds the db-migrator image in ACR (tagged with a hash of its sources, so unchanged code is not rebuilt) and points
# the migration and maintenance jobs at it. Prints the image reference. Used by the postprovision hook and
# scripts/backfill-history.sh; reads the azd environment values (Terraform outputs).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${AZURE_RESOURCE_GROUP:?}" "${AZURE_CONTAINER_REGISTRY_NAME:?}" "${AZURE_CONTAINER_REGISTRY_ENDPOINT:?}"
: "${MIGRATOR_JOB_NAME:?}" "${MAINTENANCE_JOB_NAME:?}"

# Every module the migrator is built from.
TAG="$(cd "$ROOT" && find pom.xml services/Dockerfile libs/payload-codecs libs/device-fleet libs/db-schema services/db-migrator \
        -type f -not -path '*/target/*' -print0 | sort -z | xargs -0 sha1sum | sha1sum | cut -c1-12)"
IMAGE="$AZURE_CONTAINER_REGISTRY_ENDPOINT/sdp/db-migrator:$TAG"
if az acr repository show -n "$AZURE_CONTAINER_REGISTRY_NAME" --image "sdp/db-migrator:$TAG" -o none 2>/dev/null; then
  echo "Migrator image $TAG already built" >&2
else
  echo "Building migrator image $TAG in ACR..." >&2
  az acr build -r "$AZURE_CONTAINER_REGISTRY_NAME" -t "sdp/db-migrator:$TAG" -f "$ROOT/services/Dockerfile" \
    --build-arg SERVICE=db-migrator --no-logs "$ROOT" -o none
fi
for job in "$MIGRATOR_JOB_NAME" "$MAINTENANCE_JOB_NAME"; do
  az containerapp job update -n "$job" -g "$AZURE_RESOURCE_GROUP" --image "$IMAGE" -o none
done
echo "$IMAGE"
