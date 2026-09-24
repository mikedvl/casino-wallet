#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

readonly HEALTH_TIMEOUT=180

usage() {
  cat <<'EOF'
Usage:
  ./scripts/start-demo.sh [start|stop|reset]

Commands:
  start   Build and start the demo stack (default).
  stop    Stop the demo stack and preserve PostgreSQL financial/demo history.
  reset   Stop the demo stack and delete the local PostgreSQL demo volume/history.
  help    Show this help (also -h or --help).

Stop and reset preserve application images. Reset does not restart the stack.
EOF
}

if [[ $# -gt 1 ]]; then
  printf 'Expected at most one command.\n' >&2
  usage >&2
  exit 2
fi
action="${1-start}"
case "$action" in
  start|stop|reset) ;;
  help|-h|--help) usage; exit 0 ;;
  *) printf 'Unknown command: %s\n' "$action" >&2; usage >&2; exit 2 ;;
esac

fail() {
  printf 'Demo %s failed: %s\n' "$action" "$*" >&2
  exit 1
}

check_docker_base() {
  command -v docker >/dev/null 2>&1 \
    || fail 'Docker is not installed or not on PATH. Install Docker Desktop on macOS/Windows or Docker Engine on Linux.'
  docker info >/dev/null 2>&1 \
    || fail 'Docker is installed, but its daemon is unreachable. Start Docker Desktop or the Docker daemon and try again.'

  local compose_version
  compose_version="$(docker compose version --short 2>/dev/null)" \
    || fail 'Docker Compose v2 is required. Install the Compose v2 plugin or update Docker Desktop.'
  case "$compose_version" in
    2.*|v2.*) ;;
    *) fail 'Docker Compose v2 is required; legacy docker-compose v1 is not supported.' ;;
  esac
}

check_start_capabilities() {
  local compose_help
  compose_help="$(docker compose up --help)" \
    || fail 'Cannot read Docker Compose startup options.'
  [[ "$compose_help" == *--wait* && "$compose_help" == *--wait-timeout* ]] \
    || fail 'Update Docker Compose v2: this script requires up --wait and --wait-timeout.'
}

show_failure_diagnostics() {
  printf '\nCompose status:\n' >&2
  docker compose ps --all >&2 || true
  printf '\nRecent logs (at most 60 lines per service):\n' >&2
  docker compose logs --no-color --tail=60 postgres backend frontend >&2 || true
  printf '\nFor a bind/address-already-in-use or port-is-already-allocated error, check the configured host ports (defaults: 4200, 8080, 15432).\n' >&2
  printf 'Stop the conflicting process or set FRONTEND_PORT, BACKEND_PORT or POSTGRES_PORT in .env or your shell, then retry.\n' >&2
  printf '\nInspect further from the repository root:\n  cd %q\n  docker compose ps --all\n  docker compose logs -f\n' "$REPO_ROOT" >&2
  printf 'Containers and PostgreSQL data have been kept for inspection.\n' >&2
}

startup_failed() {
  printf 'Demo startup failed: %s\n' "$*" >&2
  show_failure_diagnostics
  exit 1
}

printf 'Checking Docker and Docker Compose prerequisites...\n'
check_docker_base
if [[ "$action" == start ]]; then
  check_start_capabilities
fi

printf 'Validating Compose configuration...\n'
docker compose config --quiet || fail 'Invalid Compose configuration. Review compose.yaml and any .env or shell overrides.'

case "$action" in
  stop)
    printf 'Stopping Casino Wallet...\n'
    docker compose down --remove-orphans \
      || fail 'Could not stop the stack. Inspect it with docker compose ps --all.'
    printf 'Casino Wallet stopped. PostgreSQL demo data and application images were preserved.\n'
    exit 0
    ;;
  reset)
    printf 'Resetting Casino Wallet demo data.\nThis deletes the local PostgreSQL demo volume and financial history.\n'
    docker compose down -v --remove-orphans \
      || fail 'Could not finish the reset. Inspect it with docker compose ps --all.'
    printf 'Casino Wallet reset. Local PostgreSQL demo data was deleted; application images were preserved. The stack remains stopped.\n'
    exit 0
    ;;
esac

printf 'Building and starting the demo; waiting up to %s seconds for healthchecks...\n' "$HEALTH_TIMEOUT"
docker compose up -d --build --wait --wait-timeout "$HEALTH_TIMEOUT" \
  || startup_failed 'Build, startup or health waiting did not complete successfully.'

for service in postgres backend frontend; do
  container_id="$(docker compose ps --all --quiet "$service")" \
    || startup_failed "Cannot find the $service container."
  [[ -n "$container_id" && "$container_id" != *$'\n'* ]] \
    || startup_failed "Expected one $service container."
  container_state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$container_id")" \
    || startup_failed "Cannot inspect the $service container."
  [[ "$container_state" == 'running healthy' ]] \
    || startup_failed "$service is $container_state, expected running healthy."
done

frontend_address="$(docker compose port frontend 8080)" \
  || startup_failed 'Cannot determine the frontend host port.'
backend_address="$(docker compose port backend 8080)" \
  || startup_failed 'Cannot determine the backend host port.'
postgres_address="$(docker compose port postgres 5432)" \
  || startup_failed 'Cannot determine the PostgreSQL host port.'

cat <<EOF

Casino Wallet is ready.

Services:
  postgres  healthy
  backend   healthy
  frontend  healthy

Application:
  http://localhost:${frontend_address##*:}
Backend health:
  http://localhost:${backend_address##*:}/actuator/health
Backend readiness:
  http://localhost:${backend_address##*:}/actuator/health/readiness
Backend metrics:
  http://localhost:${backend_address##*:}/actuator/metrics
Frontend health:
  http://localhost:${frontend_address##*:}/health
PostgreSQL:
  localhost:${postgres_address##*:} (Compose network: postgres:5432)

The application is running. From the repository root:
  Stop (preserve database):
    ./scripts/start-demo.sh stop
  Reset local demo data intentionally (deletes PostgreSQL volume and financial history):
    ./scripts/start-demo.sh reset
  Native alternatives:
    docker compose down
    docker compose down -v
EOF
printf '\nRepository root: %s\n' "$REPO_ROOT"
