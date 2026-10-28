#!/usr/bin/env bash
# Pre-provision check (M6 task 1.3): fail fast when the AI device analyst's model cannot be deployed in the region,
# instead of failing late in `terraform apply`. Mirrors the defaults of infra/main.tf (module "foundry").
#
# Usage: scripts/check-model-quota.sh   (reads AZURE_SUBSCRIPTION_ID, AZURE_ENV_NAME, AZURE_LOCATION, AGENT_MODEL_*)
set -euo pipefail

name="${AGENT_MODEL_NAME:-gpt-6-luna}"
version="${AGENT_MODEL_VERSION:-2026-09-22}"
sku="${AGENT_MODEL_SKU:-DataZoneStandard}"
capacity="${AGENT_MODEL_CAPACITY:-50}"
location="${AZURE_LOCATION:-francecentral}"
subscription="${AZURE_SUBSCRIPTION_ID:?AZURE_SUBSCRIPTION_ID is required}"
resource_group="rg-${AZURE_ENV_NAME:?AZURE_ENV_NAME is required}"

fail() {
  echo "ERROR: model preflight: $*" >&2
  echo "Choose another model, SKU, capacity or region (azd env set AGENT_MODEL_NAME / AGENT_MODEL_VERSION / AGENT_MODEL_SKU / AGENT_MODEL_CAPACITY / AZURE_LOCATION) or request quota: https://aka.ms/oai/quotaincrease" >&2
  exit 1
}

if ! [[ "$capacity" =~ ^[1-9][0-9]*$ ]]; then
  fail "AGENT_MODEL_CAPACITY must be a positive integer (thousands of tokens per minute), got '$capacity'."
fi

offered=$(az cognitiveservices model list --subscription "$subscription" -l "$location" \
  --query "[?kind=='AIServices' && model.name=='$name' && model.version=='$version'] | [0].model.skus[?name=='$sku'] | length(@)" \
  -o tsv 2>/dev/null || true)
if [[ "${offered:-0}" == "0" || -z "$offered" ]]; then
  fail "$name version $version is not offered with SKU $sku in $location."
fi

read -r used limit < <(az cognitiveservices usage list --subscription "$subscription" -l "$location" \
  --query "[?name.value=='OpenAI.$sku.$name'] | [0].join(' ', [to_string(currentValue), to_string(limit)])" -o tsv) || true
if [[ -z "${limit:-}" || "$limit" == "None" ]]; then
  fail "no $sku quota found for $name in $location."
fi

# Capacity already held by this environment's deployment is reused, not added.
existing=0
if [[ "$(az group exists --subscription "$subscription" -n "$resource_group")" == "true" ]]; then
  for account in $(az cognitiveservices account list --subscription "$subscription" -g "$resource_group" --query "[].name" -o tsv); do
    held=$(az cognitiveservices account deployment list --subscription "$subscription" -g "$resource_group" -n "$account" \
      --query "[?name=='$name' && sku.name=='$sku'] | [0].sku.capacity" -o tsv)
    [[ -n "$held" && "$held" != "None" ]] && existing=$((existing + held))
  done
fi

available=$(( ${limit%.*} - ${used%.*} + existing ))
if (( capacity > available )); then
  fail "$name ($sku) needs ${capacity}K tokens/min in $location but only ${available}K are available (limit ${limit%.*}K, used ${used%.*}K, of which ${existing}K by this environment)."
fi
echo "Model preflight: $name $version ($sku) ${capacity}K tokens/min in $location — OK (${available}K available)."
