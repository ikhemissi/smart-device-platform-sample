#!/usr/bin/env bash
# azd postdeploy hook. Health probes depend on the deployed image (they are not applied to the placeholder image of a
# brand-new environment), so after the first deployment of a new environment Terraform is applied once more to
# enable them. Later deployments skip this (probes already present).
set -euo pipefail
: "${AZURE_RESOURCE_GROUP:?}" "${DECODER_APP_NAME:?}"
probes="$(az containerapp show -n "$DECODER_APP_NAME" -g "$AZURE_RESOURCE_GROUP" \
           --query 'length(properties.template.containers[0].probes || `[]`)' -o tsv)"
if [[ "${probes:-0}" -gt 0 ]]; then
  echo "Health probes already configured"
  exit 0
fi
echo "First deployment of this environment: applying the infrastructure again to enable health probes"
azd provision -e "$AZURE_ENV_NAME" --no-prompt
