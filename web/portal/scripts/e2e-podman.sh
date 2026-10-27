#!/usr/bin/env bash
# Runs the end-to-end tests in the official Playwright image (for machines without the browsers' system libraries).
# No network is needed: the site is served locally and sign-in/API are faked by the tests.
set -euo pipefail
cd "$(dirname "$0")/.."
version="$(node -p "require('@playwright/test/package.json').version")"
[[ -d dist ]] || npm run build
exec podman run --rm --network=none --userns=keep-id -e CI=1 -v "$PWD:/work:Z" -w /work \
  "mcr.microsoft.com/playwright:v${version}-noble" npx playwright test "$@" 2> >(grep -v "CNI config" >&2)
