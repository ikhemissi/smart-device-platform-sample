#!/usr/bin/env bash
# Dependency vulnerability scan (spec continuous-integration): Maven, npm and Python (uv) lock files and the shipped
# container images. All findings are written as SARIF (for code scanning) when --sarif-dir is given; the scan
# fails when a High or Critical finding with a fixed version is not accepted in .trivyignore.yaml (each acceptance has
# a statement and an expiry date). Requires Trivy (https://trivy.dev).
#   scripts/scan-vulnerabilities.sh [--sarif-dir <dir>]        (needs JDK 25 for ./mvnw, and Docker or Podman)
set -euo pipefail
cd "$(dirname "$0")/.."

SARIF_DIR=""
[[ "${1:-}" == "--sarif-dir" ]] && SARIF_DIR="$2"

# Shipped images: the Java services' runtime base image (the services add only their jars, scanned through Maven),
# and the AI agent's image as built from its Dockerfile (Docker, or Podman when Docker is absent).
ENGINE="${CONTAINER_ENGINE:-}"
if [[ -z "$ENGINE" ]]; then
  if docker info >/dev/null 2>&1; then ENGINE=docker; else ENGINE=podman; fi
fi
AGENT_IMAGE_TAR="$(mktemp -d)/device-analyst.tar"
"$ENGINE" build --quiet -t localhost/sdp-device-analyst:scan agents/device-analyst >/dev/null
"$ENGINE" save -o "$AGENT_IMAGE_TAR" localhost/sdp-device-analyst:scan
IMAGES=(
  "$(sed -n 's/^ARG RUNTIME_IMAGE=//p' services/Dockerfile)"
  "--input $AGENT_IMAGE_TAR"
)
# Maven dependencies are resolved by Maven into the local repository first; Trivy then reads them offline (resolving
# them itself from Maven Central gets rate-limited on shared CI runners).
./mvnw -B -ntp -q dependency:go-offline
COMMON=(--scanners vuln --offline-scan --ignorefile .trivyignore.yaml --skip-dirs "**/node_modules,**/.venv,**/target,**/dist" --quiet)

status=0
if [[ -n "$SARIF_DIR" ]]; then
  mkdir -p "$SARIF_DIR"
  trivy fs "${COMMON[@]}" --format sarif --output "$SARIF_DIR/dependencies.sarif" .
  for image in "${IMAGES[@]}"; do
    name="$(basename "${image#--input }" | tr '/:.' '---')"
    # shellcheck disable=SC2086 # "--input <tar>" is two words on purpose
    trivy image --scanners vuln --ignorefile .trivyignore.yaml --quiet --format sarif --output "$SARIF_DIR/image-$name.sarif" $image
  done
fi

echo "== Dependencies (Maven, npm, Python): High and Critical findings with a fix"
trivy fs "${COMMON[@]}" --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 . || status=1
for image in "${IMAGES[@]}"; do
  echo "== Image ${image#--input }: High and Critical findings with a fix"
  # shellcheck disable=SC2086
  trivy image --scanners vuln --ignorefile .trivyignore.yaml --quiet --severity HIGH,CRITICAL --ignore-unfixed \
    --exit-code 1 $image || status=1
done
exit $status
