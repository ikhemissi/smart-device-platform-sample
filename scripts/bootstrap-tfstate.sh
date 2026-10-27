#!/usr/bin/env bash
# One-off (per subscription) creation of the Terraform remote state store used by azd (ADR-0008, guidelines §7):
# a storage account with shared keys disabled, accessed with Entra ID. Idempotent.
#
#   scripts/bootstrap-tfstate.sh [location]
#
# If an azd environment is selected, its RS_* variables are set so `azd provision` uses this backend. Also run by
# the azd preprovision hook, so a new environment needs no manual step.
set -euo pipefail

LOCATION="${1:-${AZURE_LOCATION:-francecentral}}"
SUBSCRIPTION_ID="$(az account show --query id -o tsv)"
RG="${RS_RESOURCE_GROUP:-rg-sdp-tfstate}"
ACCOUNT="${RS_STORAGE_ACCOUNT:-sttfsdp${SUBSCRIPTION_ID:0:8}}"
CONTAINER="${RS_CONTAINER_NAME:-tfstate}"
ME="$(az ad signed-in-user show --query id -o tsv)"

# Tenant governance: both tags are required on every resource of this demonstrator (implementation guidelines §7).
TAGS=(purpose=terraform-state SecurityControl=Ignore CostControl=Ignore)

az group create -n "$RG" -l "$LOCATION" --tags "${TAGS[@]}" -o none
if ! az storage account show -n "$ACCOUNT" -g "$RG" -o none 2>/dev/null; then
  az storage account create -n "$ACCOUNT" -g "$RG" -l "$LOCATION" --sku Standard_LRS --kind StorageV2 \
    --allow-shared-key-access false --allow-blob-public-access false --min-tls-version TLS1_2 \
    --public-network-access Enabled --tags "${TAGS[@]}" -o none
fi
az storage account update -n "$ACCOUNT" -g "$RG" --public-network-access Enabled --tags "${TAGS[@]}" -o none
ACCOUNT_ID="$(az storage account show -n "$ACCOUNT" -g "$RG" --query id -o tsv)"
az storage account blob-service-properties update --account-name "$ACCOUNT" -g "$RG" --enable-versioning true -o none
if [[ -z "$(az role assignment list --assignee "$ME" --scope "$ACCOUNT_ID" --role "Storage Blob Data Contributor" --query '[0].id' -o tsv)" ]]; then
  az role assignment create --assignee-object-id "$ME" --assignee-principal-type User \
    --role "Storage Blob Data Contributor" --scope "$ACCOUNT_ID" -o none
  echo "Waiting for role propagation..."
  sleep 45
fi
az storage container create -n "$CONTAINER" --account-name "$ACCOUNT" --auth-mode login -o none

# Inside azd hooks AZURE_ENV_NAME designates the environment being provisioned.
ENV_ARGS=()
[[ -n "${AZURE_ENV_NAME:-}" ]] && ENV_ARGS=(-e "$AZURE_ENV_NAME")
if azd env get-values "${ENV_ARGS[@]}" >/dev/null 2>&1; then
  azd env set "${ENV_ARGS[@]}" RS_RESOURCE_GROUP "$RG" >/dev/null
  azd env set "${ENV_ARGS[@]}" RS_STORAGE_ACCOUNT "$ACCOUNT" >/dev/null
  azd env set "${ENV_ARGS[@]}" RS_CONTAINER_NAME "$CONTAINER" >/dev/null
  echo "azd environment configured to use the remote state backend."
fi
echo "Terraform state: $ACCOUNT/$CONTAINER (resource group $RG)"
