#!/usr/bin/env bash
# Writes agents/anomaly-detector/.env from the current azd environment, to run the anomaly detector locally with your
# own Entra identity (PostgreSQL Entra administrator, Foundry User on the AI account: granted to the deployer).
#   scripts/anomaly-env.sh [-e <environment>]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_ARGS=()
[[ "${1:-}" == "-e" ]] && ENV_ARGS=(-e "$2")
value() { azd env get-value "$@" "${ENV_ARGS[@]}" 2>/dev/null; }
cat > "$ROOT/agents/anomaly-detector/.env" <<ENV
SDP_DB_HOST=$(value POSTGRES_HOST)
SDP_DB_NAME=$(value POSTGRES_DATABASE)
SDP_DB_USER=$(az ad signed-in-user show --query userPrincipalName -o tsv)
SDP_DECISION_ENDPOINT=$(value FOUNDRY_ACCOUNT_ENDPOINT)
SDP_DECISION_DEPLOYMENT=$(value DECISION_MODEL_DEPLOYMENT_NAME)
AZURE_OPENAI_ENDPOINT=$(value AZURE_OPENAI_ENDPOINT)
FOUNDRY_MODEL_DEPLOYMENT_NAME=$(value AGENT_MODEL_DEPLOYMENT_NAME)
SDP_ANOMALY_SITES=$(azd env get-values "${ENV_ARGS[@]}" | sed -n 's/^ANOMALY_SITES="\(..*\)"$/\1/p' | grep . || echo paris-01)
ENV
echo "Wrote agents/anomaly-detector/.env"
