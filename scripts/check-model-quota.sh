#!/usr/bin/env bash
# Pre-provision check (M6 task 1.3, M7 task 1.2): fail fast when a Foundry model cannot be deployed in the region,
# instead of failing late in `terraform apply`. Mirrors the defaults of infra/main.tf (module "foundry").
#
# Usage: scripts/check-model-quota.sh
#   reads AZURE_SUBSCRIPTION_ID, AZURE_ENV_NAME, AZURE_LOCATION, AGENT_MODEL_*, ANOMALY_DETECTION_ENABLED,
#   MAINTENANCE_ENABLED, DECISION_MODEL_CAPACITY
set -euo pipefail

location="${AZURE_LOCATION:-francecentral}"
subscription="${AZURE_SUBSCRIPTION_ID:?AZURE_SUBSCRIPTION_ID is required}"
resource_group="rg-${AZURE_ENV_NAME:?AZURE_ENV_NAME is required}"

# check <model> <version> <sku> <capacity> <quota prefix> <deployment name> <how to change it>
check() {
  local name="$1" version="$2" sku="$3" capacity="$4" quota_prefix="$5" deployment="$6" hint="$7"
  fail() {
    echo "ERROR: model preflight: $*" >&2
    echo "$hint" >&2
    exit 1
  }

  if ! [[ "$capacity" =~ ^[1-9][0-9]*$ ]]; then
    fail "the capacity of $name must be a positive integer, got '$capacity'."
  fi

  local offered
  offered=$(az cognitiveservices model list --subscription "$subscription" -l "$location" \
    --query "[?kind=='AIServices' && model.name=='$name' && model.version=='$version'] | [0].model.skus[?name=='$sku'] | length(@)" \
    -o tsv 2>/dev/null || true)
  if [[ "${offered:-0}" == "0" || -z "$offered" ]]; then
    fail "$name version $version is not offered with SKU $sku in $location."
  fi

  local used limit
  read -r used limit < <(az cognitiveservices usage list --subscription "$subscription" -l "$location" \
    --query "[?name.value=='$quota_prefix.$sku.$name'] | [0].join(' ', [to_string(currentValue), to_string(limit)])" -o tsv) || true
  if [[ -z "${limit:-}" || "$limit" == "None" ]]; then
    fail "no $sku quota found for $name in $location."
  fi

  # Capacity already held by this environment's deployment is reused, not added.
  local existing=0 held account
  if [[ "$(az group exists --subscription "$subscription" -n "$resource_group")" == "true" ]]; then
    for account in $(az cognitiveservices account list --subscription "$subscription" -g "$resource_group" --query "[].name" -o tsv); do
      held=$(az cognitiveservices account deployment list --subscription "$subscription" -g "$resource_group" -n "$account" \
        --query "[?name=='$deployment' && sku.name=='$sku'] | [0].sku.capacity" -o tsv)
      [[ -n "$held" && "$held" != "None" ]] && existing=$((existing + held))
    done
  fi

  local available=$(( ${limit%.*} - ${used%.*} + existing ))
  if (( capacity > available )); then
    fail "$name ($sku) needs $capacity capacity units in $location but only $available are available (limit ${limit%.*}, used ${used%.*}, of which $existing by this environment)."
  fi
  echo "Model preflight: $name $version ($sku) capacity $capacity in $location — OK ($available available)."
}

check "${AGENT_MODEL_NAME:-gpt-6-luna}" "${AGENT_MODEL_VERSION:-2026-09-22}" "${AGENT_MODEL_SKU:-DataZoneStandard}" \
  "${AGENT_MODEL_CAPACITY:-50}" OpenAI "${AGENT_MODEL_NAME:-gpt-6-luna}" \
  "Choose another model, SKU, capacity or region (azd env set AGENT_MODEL_NAME / AGENT_MODEL_VERSION / AGENT_MODEL_SKU / AGENT_MODEL_CAPACITY / AZURE_LOCATION) or request quota: https://aka.ms/oai/quotaincrease"

# Anomaly detection (M7) and maintenance (M9): Microsoft-Decision-1, capacity units of 1 request and 1,000 tokens per
# minute; deployed when either feature is enabled.
anomalies="${ANOMALY_DETECTION_ENABLED:-true}"
maintenance="${MAINTENANCE_ENABLED:-true}"
if [[ "${anomalies,,}" == "false" && "${maintenance,,}" == "false" ]]; then
  echo "Model preflight: anomaly detection and maintenance disabled (ANOMALY_DETECTION_ENABLED=false, MAINTENANCE_ENABLED=false), Microsoft-Decision-1 not checked."
else
  check Microsoft-Decision-1 1 DataZoneStandard "${DECISION_MODEL_CAPACITY:-30}" AIServices decision-1 \
    "Lower DECISION_MODEL_CAPACITY, or switch anomaly detection and maintenance off for this environment: azd env set ANOMALY_DETECTION_ENABLED false; azd env set MAINTENANCE_ENABLED false"
fi
