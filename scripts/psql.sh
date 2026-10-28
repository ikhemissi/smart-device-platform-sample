#!/usr/bin/env bash
# Opens psql on the database of the current azd environment with YOUR Entra identity (token as password), e.g.
#   scripts/psql.sh -c "SELECT count(*) FROM sdp.measurements"
#   scripts/psql.sh -e sdp-e2e            # another azd environment (interactive)
# Uses a local psql if available, otherwise the postgres:18 image with podman or docker.
set -euo pipefail

ENV_ARGS=()
if [[ "${1:-}" == "-e" ]]; then
  ENV_ARGS=(-e "$2")
  shift 2
fi
HOST="$(azd env get-value "${ENV_ARGS[@]}" POSTGRES_HOST 2>/dev/null)"
DATABASE="$(azd env get-value "${ENV_ARGS[@]}" POSTGRES_DATABASE 2>/dev/null || echo sdp)"
[[ -n "$HOST" ]] || { echo "POSTGRES_HOST not found in the azd environment" >&2; exit 1; }
USER_NAME="$(az ad signed-in-user show --query userPrincipalName -o tsv)"
PGPASSWORD="$(az account get-access-token --resource-type oss-rdbms --query accessToken -o tsv)"
export PGPASSWORD
CONNECTION="host=$HOST port=5432 dbname=$DATABASE user=$USER_NAME sslmode=require"

if command -v psql >/dev/null; then
  exec psql "$CONNECTION" -v ON_ERROR_STOP=1 "$@"
fi
CLI="$(command -v podman || command -v docker || true)"
[[ -n "$CLI" ]] || { echo "psql, podman or docker is required" >&2; exit 1; }
TTY=()
[[ -t 0 && $# -eq 0 ]] && TTY=(-it)
[[ ! -t 0 ]] && TTY=(-i)   # SQL piped on stdin
NETWORK=()
[[ "$(basename "$CLI")" == podman ]] && NETWORK=(--network slirp4netns)
exec "$CLI" run --rm "${TTY[@]}" "${NETWORK[@]}" -e PGPASSWORD docker.io/library/postgres:18 \
  psql "$CONNECTION" -v ON_ERROR_STOP=1 "$@" 2> >(grep -v "CNI config" >&2)
