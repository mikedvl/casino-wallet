#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

readonly HEALTH_TIMEOUT=300
MONITORING_SERVICES=(prometheus grafana elasticsearch kibana filebeat otel-collector jaeger)
ALL_SERVICES=(postgres backend frontend "${MONITORING_SERVICES[@]}")

usage() {
  cat <<'EOF'
Usage:
  ./scripts/start-observability.sh [start|stop]

Commands:
  start   Build and start Casino Wallet with the complete observability stack (default).
  stop    Stop only observability services; keep Casino Wallet running and preserve data.
  help    Show this help (also -h or --help).

To stop the application too, use ./scripts/start-demo.sh stop.
EOF
}

if [[ $# -gt 1 ]]; then
  printf 'Expected at most one command.\n' >&2
  usage >&2
  exit 2
fi
action="${1-start}"
case "$action" in
  start|stop) ;;
  help|-h|--help) usage; exit 0 ;;
  *) printf 'Unknown command: %s\n' "$action" >&2; usage >&2; exit 2 ;;
esac

compose() {
  docker compose -f compose.yaml -f compose.observability.yaml --profile observability "$@"
}

fail() {
  printf 'Observability %s failed: %s\n' "$action" "$*" >&2
  exit 1
}

check_docker() {
  command -v docker >/dev/null 2>&1 \
    || fail 'Docker is not installed or not on PATH. Install Docker Desktop on macOS/Windows or Docker Engine on Linux.'
  docker info >/dev/null 2>&1 \
    || fail 'Docker is installed, but its daemon is unreachable. Start Docker Desktop or the Docker daemon and try again.'

  local version
  version="$(docker compose version --short 2>/dev/null)" \
    || fail 'Docker Compose v2 is required. Install the Compose v2 plugin or update Docker Desktop.'
  case "$version" in
    2.*|v2.*) ;;
    *) fail 'Docker Compose v2 is required; legacy docker-compose v1 is not supported.' ;;
  esac
}

check_start_capabilities() {
  local options
  options="$(docker compose up --help)" || fail 'Cannot read Docker Compose startup options.'
  [[ "$options" == *--wait[[:space:]]* && "$options" == *--wait-timeout[[:space:]]* ]] \
    || fail 'Update Docker Compose v2: this script requires up --wait and --wait-timeout.'
}

show_failure_diagnostics() {
  printf '\nCompose status:\n' >&2
  compose ps --all >&2 || true
  printf '\nRecent logs (at most 60 lines per service):\n' >&2
  compose logs --no-color --tail=60 "${ALL_SERVICES[@]}" >&2 || true
  printf '\nFor port-bind errors, check configured host ports (defaults: 4200, 8080, 15432, 9090, 3000, 9200, 5601, 16686).\n' >&2
  printf 'Resolve the conflict or set explicit port overrides in .env or your shell.\n' >&2
  printf '\nInspect further from the repository root:\n  cd %q\n' "$REPO_ROOT" >&2
  printf '  docker compose -f compose.yaml -f compose.observability.yaml --profile observability ps --all\n' >&2
  printf '  docker compose -f compose.yaml -f compose.observability.yaml --profile observability logs -f\n' >&2
  printf 'Containers and data have been kept for inspection.\n' >&2
}

operation_failed() {
  printf 'Observability %s failed: %s\n' "$action" "$*" >&2
  show_failure_diagnostics
  exit 1
}

published_address() {
  local address
  address="$(compose port "$1" "$2")" || return 1
  [[ -n "$address" && "$address" != *$'\n'* && "${address##*:}" =~ ^[0-9]+$ ]] || return 1
  printf 'localhost:%s' "${address##*:}"
}

printf 'Checking Docker and Docker Compose prerequisites...\n'
check_docker
if [[ "$action" == start ]]; then
  check_start_capabilities
fi

printf 'Validating the combined Compose configuration...\n'
compose config --quiet \
  || fail 'Invalid Compose configuration. Review compose.yaml, compose.observability.yaml and any .env or shell overrides.'

if [[ "$action" == stop ]]; then
  printf 'Stopping observability services only...\n'
  compose stop "${MONITORING_SERVICES[@]}" || operation_failed 'Could not stop all observability services.'
  printf 'Observability stopped. Casino Wallet application containers were left unchanged.\n'
  printf 'All containers, data volumes and images were preserved.\n'
  printf 'To stop the application too: ./scripts/start-demo.sh stop\n'
  exit 0
fi

printf 'Enhanced observability includes Elasticsearch and Kibana and needs more Docker memory than the base application (about 8 GiB was verified).\n'
printf 'Building and starting the complete stack; waiting up to %s seconds for healthchecks after the build...\n' "$HEALTH_TIMEOUT"
compose up -d --build --wait --wait-timeout "$HEALTH_TIMEOUT" \
  || operation_failed 'Build, startup or health waiting did not complete successfully.'

printf '\nServices:\n'
for service in "${ALL_SERVICES[@]}"; do
  container_id="$(compose ps --all --quiet "$service")" \
    || operation_failed "Cannot find the $service container."
  [[ -n "$container_id" && "$container_id" != *$'\n'* ]] \
    || operation_failed "Expected one $service container."
  container_state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$container_id")" \
    || operation_failed "Cannot inspect the $service container."
  [[ "$container_state" == 'running healthy' ]] \
    || operation_failed "$service is $container_state, expected running healthy."
  printf '  %-16s healthy\n' "$service"
done

frontend_address="$(published_address frontend 8080)" || operation_failed 'Cannot determine the frontend host port.'
backend_address="$(published_address backend 8080)" || operation_failed 'Cannot determine the backend host port.'
postgres_address="$(published_address postgres 5432)" || operation_failed 'Cannot determine the PostgreSQL host port.'
prometheus_address="$(published_address prometheus 9090)" || operation_failed 'Cannot determine the Prometheus host port.'
grafana_address="$(published_address grafana 3000)" || operation_failed 'Cannot determine the Grafana host port.'
elasticsearch_address="$(published_address elasticsearch 9200)" || operation_failed 'Cannot determine the Elasticsearch host port.'
kibana_address="$(published_address kibana 5601)" || operation_failed 'Cannot determine the Kibana host port.'
jaeger_address="$(published_address jaeger 16686)" || operation_failed 'Cannot determine the Jaeger host port.'

cat <<EOF

Casino Wallet with enhanced observability is ready.

Application:
  Casino Wallet   http://$frontend_address
  Backend         http://$backend_address
  Readiness       http://$backend_address/actuator/health/readiness
  PostgreSQL      $postgres_address (Compose network: postgres:5432)
Metrics:
  Prometheus      http://$prometheus_address
  Grafana         http://$grafana_address
Logs:
  Elasticsearch   http://$elasticsearch_address
  Kibana          http://$kibana_address
Traces:
  Jaeger          http://$jaeger_address

Correlation:
  X-Request-ID    request/support correlation
  trace_id        distributed trace
  span_id         current trace span

From the repository root:
  Stop monitoring only:       ./scripts/start-observability.sh stop
  Stop the application too:   ./scripts/start-demo.sh stop
EOF
