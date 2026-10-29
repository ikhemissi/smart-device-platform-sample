#!/usr/bin/env bash
# Builds the anomaly detector image in ACR (tagged with a hash of its sources, so unchanged code is not rebuilt) and
# points the anomaly detector job at it (M7 design D1). Prints the image reference. Does nothing when anomaly detection
# is disabled (no job). Used by the postprovision hook; reads the azd environment values (Terraform outputs).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${AZURE_RESOURCE_GROUP:?}" "${AZURE_CONTAINER_REGISTRY_NAME:?}" "${AZURE_CONTAINER_REGISTRY_ENDPOINT:?}"
if [[ -z "${ANOMALY_DETECTOR_JOB_NAME:-}" ]]; then
  echo "Anomaly detection disabled: no detector image to build" >&2
  exit 0
fi

PROJECT="agents/anomaly-detector"
# Exactly the files the image is built from (see its Dockerfile and .dockerignore).
TAG="$(cd "$ROOT/$PROJECT" && find Dockerfile .dockerignore pyproject.toml uv.lock README.md src -type f \
        -not -path '*/__pycache__/*' -print0 | sort -z | xargs -0 sha1sum | sha1sum | cut -c1-12)"
IMAGE="$AZURE_CONTAINER_REGISTRY_ENDPOINT/sdp/anomaly-detector:$TAG"
if az acr repository show -n "$AZURE_CONTAINER_REGISTRY_NAME" --image "sdp/anomaly-detector:$TAG" -o none 2>/dev/null; then
  echo "Anomaly detector image $TAG already built" >&2
else
  echo "Building anomaly detector image $TAG in ACR..." >&2
  az acr build -r "$AZURE_CONTAINER_REGISTRY_NAME" -t "sdp/anomaly-detector:$TAG" --no-logs "$ROOT/$PROJECT" -o none
fi
az containerapp job update -n "$ANOMALY_DETECTOR_JOB_NAME" -g "$AZURE_RESOURCE_GROUP" --image "$IMAGE" -o none
echo "$IMAGE"
