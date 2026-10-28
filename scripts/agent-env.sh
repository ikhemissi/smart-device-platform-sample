#!/usr/bin/env bash
# Writes agents/device-analyst/.env from the current azd environment, to run the device analyst locally with your own
# Entra identity (Foundry User on the AI account, Redis access policy: granted to the deployer by Terraform).
#   scripts/agent-env.sh [-e <environment>]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
value() { azd env get-value "$@" "${ENV_ARGS[@]}" 2>/dev/null; }
ENV_ARGS=()
[[ "${1:-}" == "-e" ]] && ENV_ARGS=(-e "$2")
client_id="$(value API_CLIENT_ID)"
cat > "$ROOT/agents/device-analyst/.env" <<ENV
SDP_ENTRA_TENANT_ID=$(value ENTRA_TENANT_ID)
SDP_API_AUDIENCES=$client_id,api://$client_id
SDP_API_BASE_URL=$(value API_BASE_URL)
AZURE_OPENAI_ENDPOINT=$(value AZURE_OPENAI_ENDPOINT)
FOUNDRY_MODEL_DEPLOYMENT_NAME=$(value AGENT_MODEL_DEPLOYMENT_NAME)
SDP_REDIS_HOST=$(value REDIS_HOST)
SDP_REDIS_PORT=10000
ENV
echo "Wrote agents/device-analyst/.env"
