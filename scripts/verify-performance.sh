#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
compose_file="$repo_root/deploy/compose/compose.yaml"
override_file="$repo_root/deploy/compose/operations-override.yaml"
verify_project=kmp-performance-check
web_port=${KMP_PERFORMANCE_WEB_PORT:-18388}
requests=${KMP_PERFORMANCE_REQUESTS:-300}
concurrency=${KMP_PERFORMANCE_CONCURRENCY:-16}
max_p95_ms=${KMP_PERFORMANCE_MAX_P95_MS:-1500}
max_error_rate=${KMP_PERFORMANCE_MAX_ERROR_RATE:-0}

compose() {
  docker compose -p "$verify_project" -f "$compose_file" -f "$override_file" "$@"
}

finish_performance() {
  result=$?
  trap - 0 1 2 15
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  containers_left=$(docker ps -a --filter label=com.docker.compose.project="$verify_project" -q | wc -l | tr -d ' ')
  volumes_left=$(docker volume ls --filter label=com.docker.compose.project="$verify_project" -q | wc -l | tr -d ' ')
  if [ "$containers_left" = 0 ] && [ "$volumes_left" = 0 ]; then
    printf 'CLEANUP_OK containers=0 volumes=0\n'
  else
    printf 'CLEANUP_FAILED containers=%s volumes=%s\n' "$containers_left" "$volumes_left" >&2
    [ "$result" -ne 0 ] || result=1
  fi
  exit "$result"
}
trap finish_performance 0
trap 'exit 130' 1 2 15

KMP_DB_NAME=kmp_performance_verify
KMP_DB_USER=kmp_performance
KMP_DB_PASSWORD=$(openssl rand -base64 24)
KMP_MINIO_ROOT_USER=kmp-performance
KMP_MINIO_ROOT_PASSWORD=$(openssl rand -base64 24)
KMP_BOOTSTRAP_ADMIN_USERNAME=admin
KMP_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 24)
KMP_MODEL_MASTER_KEY=$(openssl rand -base64 32)
KMP_SECURE_COOKIES=false
KMP_WEB_PORT=$web_port
export KMP_DB_NAME KMP_DB_USER KMP_DB_PASSWORD KMP_WEB_PORT
export KMP_MINIO_ROOT_USER KMP_MINIO_ROOT_PASSWORD
export KMP_BOOTSTRAP_ADMIN_USERNAME KMP_BOOTSTRAP_ADMIN_PASSWORD KMP_MODEL_MASTER_KEY KMP_SECURE_COOKIES

compose down --volumes --remove-orphans >/dev/null 2>&1 || true
if [ "${KMP_PERFORMANCE_BUILD:-true}" = true ]; then
  compose build api web >/dev/null
fi
compose up -d --no-build api web >/dev/null
api_id=$(compose ps -q api)
ready=false
for attempt in $(seq 1 90); do
  api_health=$(docker inspect "$api_id" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  if [ "$api_health" = healthy ] && curl -fsS "http://127.0.0.1:$web_port/" >/dev/null; then
    ready=true
    break
  fi
  sleep 2
done
[ "$ready" = true ] || { compose ps -a >&2; exit 1; }

java "$repo_root/scripts/performance/HttpLoadProbe.java" \
  "http://127.0.0.1:$web_port/api/v1/auth/csrf" \
  "$requests" "$concurrency" "$max_p95_ms" "$max_error_rate"

security_headers=$(curl -fsSI "http://127.0.0.1:$web_port/")
printf '%s\n' "$security_headers" | grep -i -q '^Content-Security-Policy:'
printf '%s\n' "$security_headers" | grep -i -q '^X-Content-Type-Options: nosniff'

printf 'PERFORMANCE_OK endpoint=/api/v1/auth/csrf requests=%s concurrency=%s max_p95_ms=%s max_error_rate=%s\n' \
  "$requests" "$concurrency" "$max_p95_ms" "$max_error_rate"
