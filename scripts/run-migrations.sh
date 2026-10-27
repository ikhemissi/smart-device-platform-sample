#!/usr/bin/env bash
# Runs the db-migrator from a developer machine.
#
#   scripts/run-migrations.sh [--local] [--tasks migrate,maintain]
#
# Azure (default): targets the PostgreSQL server of the current azd environment and authenticates with YOUR
#   Microsoft Entra identity (az login); you must be an Entra administrator of the server (dev environments only).
# --local: targets scripts/local-postgres.sh (password authentication, local only).
#
# Any SDP_* variable already set in the environment takes precedence (see services/db-migrator/README.md).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL=false
TASKS="${SDP_MIGRATOR_TASKS:-migrate,maintain}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --local) LOCAL=true; shift ;;
    --tasks) TASKS="$2"; shift 2 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

azd_value() { azd env get-value "$1" 2>/dev/null || true; }

if $LOCAL; then
  export SDP_DB_URL="${SDP_DB_URL:-jdbc:postgresql://localhost:${SDP_LOCAL_DB_PORT:-55432}/sdp}"
  export SDP_DB_USERNAME="${SDP_DB_USERNAME:-sdp_admin}"
  export SDP_DB_PASSWORD="${SDP_DB_PASSWORD:-local-only}"
  export SDP_DB_PASSWORDLESS=false
else
  host="$(azd_value POSTGRES_HOST)"
  database="$(azd_value POSTGRES_DATABASE)"
  if [[ -z "${SDP_DB_URL:-}" ]]; then
    [[ -n "$host" ]] || { echo "POSTGRES_HOST not found in the azd environment (run azd provision, or set SDP_DB_URL)" >&2; exit 1; }
    export SDP_DB_URL="jdbc:postgresql://${host}:5432/${database:-sdp}?sslmode=require"
  fi
  export SDP_DB_USERNAME="${SDP_DB_USERNAME:-$(az ad signed-in-user show --query userPrincipalName -o tsv)}"
  export SDP_DB_PASSWORDLESS=true
  unset SDP_DB_PASSWORD
  export SDP_DECODER_PRINCIPAL_NAME="${SDP_DECODER_PRINCIPAL_NAME:-$(azd_value DECODER_IDENTITY_NAME)}"
  export SDP_DECODER_PRINCIPAL_OBJECT_ID="${SDP_DECODER_PRINCIPAL_OBJECT_ID:-$(azd_value DECODER_IDENTITY_PRINCIPAL_ID)}"
fi
export SDP_MIGRATOR_TASKS="$TASKS"

jar="$(ls "$ROOT"/services/db-migrator/target/db-migrator-*.jar 2>/dev/null | grep -v '\.original$' | head -n1 || true)"
if [[ -z "$jar" ]]; then
  (cd "$ROOT" && ./mvnw -q -B -pl services/db-migrator -am package -DskipTests)
  jar="$(ls "$ROOT"/services/db-migrator/target/db-migrator-*.jar | grep -v '\.original$' | head -n1)"
fi

echo "Running db-migrator (tasks: $TASKS) against ${SDP_DB_URL%%\?*} as ${SDP_DB_USERNAME}"
exec java -jar "$jar"
