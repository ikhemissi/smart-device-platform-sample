#!/usr/bin/env bash
# azd postdeploy hook. Health probes and the application port depend on the deployed image (they are not applied to
# the placeholder image of a new app), so after the first deployment of an app Terraform is applied once more to
# enable them. Later deployments skip this (probes already present).
set -euo pipefail
: "${AZURE_RESOURCE_GROUP:?}" "${DECODER_APP_NAME:?}"
pending=()
for app in "$DECODER_APP_NAME" "${DEVICE_API_APP_NAME:-}" "${PROVISIONING_API_APP_NAME:-}"; do
  [[ -n "$app" ]] || continue
  image="$(az containerapp show -n "$app" -g "$AZURE_RESOURCE_GROUP" --query 'properties.template.containers[0].image' -o tsv)"
  probes="$(az containerapp show -n "$app" -g "$AZURE_RESOURCE_GROUP" \
             --query 'length(properties.template.containers[0].probes || `[]`)' -o tsv)"
  # Apps still on the placeholder image are not deployed yet (e.g. `azd deploy <other-service>`).
  if [[ "$image" != "mcr.microsoft.com/k8se/quickstart:latest" && "${probes:-0}" -eq 0 ]]; then
    pending+=("$app")
  fi
done
if [[ ${#pending[@]} -eq 0 ]]; then
  echo "Health probes already configured"
  exit 0
fi
echo "First deployment of ${pending[*]}: applying the infrastructure again to enable health probes"
azd provision -e "$AZURE_ENV_NAME" --no-prompt
