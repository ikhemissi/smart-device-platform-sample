#!/usr/bin/env bash
# azd preprovision hook: check that the AI device analyst's model can be deployed, then make sure the Terraform remote
# state backend exists and is configured for this environment (idempotent; skips the work when RS_* are already set).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
"$ROOT/scripts/check-model-quota.sh"
if [[ -n "${RS_STORAGE_ACCOUNT:-}" && -n "${RS_RESOURCE_GROUP:-}" && -n "${RS_CONTAINER_NAME:-}" ]]; then
  echo "Terraform state backend: $RS_STORAGE_ACCOUNT/$RS_CONTAINER_NAME"
  exit 0
fi
"$ROOT/scripts/bootstrap-tfstate.sh" "${AZURE_LOCATION:-francecentral}"
