#!/usr/bin/env bash
# Runs the environment tests (e2e-env/) against the current azd environment in Chromium from the official Playwright
# image: the production build with sign-in faked and every API call forwarded to API Management with your token.
# The history cache is flushed first, so the 30-day chart is measured cold, then warm. Requires `npm run build`.
#   scripts/e2e-environment.sh [device-id] [playwright args, e.g. -g smoke]
set -euo pipefail
cd "$(dirname "$0")/.."
eval "$(azd env get-values)"
TOKEN="$(az account get-access-token --scope "$API_SCOPE" --query accessToken -o tsv)"
AZURE_EXTENSION_USE_DYNAMIC_INSTALL=yes_without_prompt az redisenterprise database flush \
  --cluster-name "$REDIS_NAME" -g "$AZURE_RESOURCE_GROUP" --only-show-errors -o none
version="$(node -p "require('@playwright/test/package.json').version")"
exec podman run --rm --network=slirp4netns --userns=keep-id -e CI=1 -e SDP_API_BASE_URL="$API_BASE_URL" -e SDP_API_TOKEN="$TOKEN" \
  -e SDP_DEVICE="${1:-paris-01-ruuvi5-001}" -v "$PWD:/work:Z" -w /work "mcr.microsoft.com/playwright:v${version}-noble" \
  npx playwright test -c playwright.env.config.ts "${@:2}" 2> >(grep -v "CNI config" >&2)
