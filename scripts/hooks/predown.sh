#!/usr/bin/env bash
# azd predown hook: delete the environment's resource group in a single operation before `terraform destroy`.
# Deleting PostgreSQL child resources one by one (Entra administrators, firewall rules) is slow and can hang;
# deleting the resource group removes the server with all of them. Terraform then finds the resources gone and
# only cleans its state. Key Vaults of the group are purged and diagnostic settings deleted (Azure keeps them after their
# resource is deleted and they would reappear on a recreated resource), so the environment can be recreated with the
# same names.
# Works after a failed provision too (outputs missing): the group name follows the rg-<environment> convention.
set -euo pipefail
RG="${AZURE_RESOURCE_GROUP:-rg-${AZURE_ENV_NAME:?}}"

if [[ "$(az group exists -n "$RG")" != "true" ]]; then
  echo "Resource group $RG does not exist"
  exit 0
fi
VAULTS="$(az keyvault list -g "$RG" --query '[].name' -o tsv)"
for id in $(az resource list -g "$RG" --query '[].id' -o tsv); do
  for setting in $(az monitor diagnostic-settings list --resource "$id" --query '[].name' -o tsv 2>/dev/null); do
    echo "Deleting diagnostic setting $setting of ${id##*/}"
    az monitor diagnostic-settings delete --resource "$id" -n "$setting" -o none
  done
done
echo "Deleting resource group $RG (this takes several minutes)..."
az group delete -n "$RG" --yes -o none
for vault in $VAULTS; do
  echo "Purging soft-deleted Key Vault $vault..."
  az keyvault purge -n "$vault" -o none
done
