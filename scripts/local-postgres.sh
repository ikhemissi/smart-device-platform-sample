#!/usr/bin/env bash
# Starts/stops a local PostgreSQL 18 + pg_partman container matching Azure Flexible Server, for local development.
#   scripts/local-postgres.sh start|stop|status
# Connection: jdbc:postgresql://localhost:${SDP_LOCAL_DB_PORT:-55432}/sdp  user sdp_admin / password local-only
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="${CONTAINER_CLI:-$(command -v podman || command -v docker || true)}"
NAME="sdp-local-postgres"
IMAGE="localhost/sdp-postgres-dev:18"
PORT="${SDP_LOCAL_DB_PORT:-55432}"

[[ -n "$CLI" ]] || { echo "podman or docker is required" >&2; exit 1; }

case "${1:-}" in
  start)
    if "$CLI" inspect "$NAME" >/dev/null 2>&1; then
      "$CLI" start "$NAME" >/dev/null
    else
      "$CLI" build -q -t "$IMAGE" "$ROOT/libs/test-support/src/main/resources/sdp-postgres" >/dev/null
      "$CLI" run -d --name "$NAME" -p "127.0.0.1:${PORT}:5432" \
        -e POSTGRES_DB=sdp -e POSTGRES_USER=sdp_admin -e POSTGRES_PASSWORD=local-only "$IMAGE" >/dev/null
    fi
    for _ in $(seq 1 30); do
      "$CLI" exec "$NAME" pg_isready -U sdp_admin -d sdp >/dev/null 2>&1 && break
      sleep 1
    done
    "$CLI" exec "$NAME" pg_isready -U sdp_admin -d sdp >/dev/null
    echo "PostgreSQL ready: jdbc:postgresql://localhost:${PORT}/sdp (sdp_admin / local-only)"
    ;;
  stop)
    "$CLI" rm -f "$NAME" >/dev/null 2>&1 || true
    echo "Stopped and removed $NAME"
    ;;
  status)
    "$CLI" ps -a --filter "name=^${NAME}$" --format '{{.Names}} {{.Status}}'
    ;;
  *)
    echo "usage: $0 start|stop|status" >&2
    exit 2
    ;;
esac
