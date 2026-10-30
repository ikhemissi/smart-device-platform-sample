#!/usr/bin/env bash
# Builds the model-based analysis image in ACR (tagged with a hash of its sources, so unchanged code is not rebuilt) and
# points the jobs that run it at it: the anomaly detector (M7 design D1) and the site digest (M8 design D1). Prints the
# image reference. Does nothing when both are disabled (no job). Used by the postprovision hook; reads the azd
# environment values (Terraform outputs).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${AZURE_RESOURCE_GROUP:?}" "${AZURE_CONTAINER_REGISTRY_NAME:?}" "${AZURE_CONTAINER_REGISTRY_ENDPOINT:?}"
JOBS=()
for job in "${ANOMALY_DETECTOR_JOB_NAME:-}" "${DIGEST_JOB_NAME:-}"; do
  [[ -n "$job" ]] && JOBS+=("$job")
done
if (( ${#JOBS[@]} == 0 )); then
  echo "Anomaly detection and site digests disabled: no image to build" >&2
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
for job in "${JOBS[@]}"; do
  az containerapp job update -n "$job" -g "$AZURE_RESOURCE_GROUP" --image "$IMAGE" -o none
done
echo "$IMAGE"
