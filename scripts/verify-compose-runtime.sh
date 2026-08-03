#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
compose_file="$repo_root/deploy/compose/compose.yaml"
verify_project=kmp-runtime-check
runtime_tmp=$(mktemp -d "${TMPDIR:-/tmp}/kmp-runtime-check.XXXXXX")
cookie_jar="$runtime_tmp/cookies.txt"
csrf_headers="$runtime_tmp/csrf-headers.txt"
login_headers="$runtime_tmp/login-headers.txt"

cleanup_compose() {
  docker compose -p "$verify_project" -f "$compose_file" \
    down --volumes --remove-orphans >/dev/null 2>&1 || true
}

cleanup_runtime() {
  cleanup_compose
  for temp_file in "$cookie_jar" "$csrf_headers" "$login_headers"; do
    if [ -f "$temp_file" ]; then
      : >"$temp_file"
      rm "$temp_file"
    fi
  done
  if [ -d "$runtime_tmp" ]; then
    rmdir "$runtime_tmp"
  fi
}
verify_stage=initialization
finish_runtime() {
  result=$?
  trap - 0 1 2 15
  if [ "$result" -ne 0 ]; then
    echo "RUNTIME_FAILED stage=$verify_stage" >&2
  fi
  cleanup_runtime
  exit "$result"
}
trap finish_runtime 0
trap 'exit 130' 1 2 15

if [ -z "${KMP_DB_PASSWORD:-}" ]; then
  KMP_DB_PASSWORD=$(openssl rand -base64 24)
fi
if [ -z "${KMP_BOOTSTRAP_ADMIN_PASSWORD:-}" ]; then
  KMP_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 24)
fi
if [ -z "${KMP_MODEL_MASTER_KEY:-}" ]; then
  KMP_MODEL_MASTER_KEY=$(openssl rand -base64 32)
fi
KMP_DB_NAME=${KMP_DB_NAME:-kmp_runtime_verify}
KMP_DB_USER=${KMP_DB_USER:-kmp_runtime}
KMP_BOOTSTRAP_ADMIN_USERNAME=admin
KMP_SECURE_COOKIES=true
export KMP_DB_NAME KMP_DB_USER KMP_DB_PASSWORD
export KMP_BOOTSTRAP_ADMIN_USERNAME KMP_BOOTSTRAP_ADMIN_PASSWORD
export KMP_MODEL_MASTER_KEY KMP_SECURE_COOKIES

cleanup_compose
verify_stage=compose-build
docker compose -p "$verify_project" -f "$compose_file" config --quiet
if [ "${KMP_RUNTIME_BUILD:-true}" = true ]; then
  docker compose -p "$verify_project" -f "$compose_file" build api worker web
fi
docker compose -p "$verify_project" -f "$compose_file" \
  up -d --no-build api worker web >/dev/null

api_id=$(docker compose -p "$verify_project" -f "$compose_file" ps -q api)
worker_id=$(docker compose -p "$verify_project" -f "$compose_file" ps -q worker)
postgres_id=$(docker compose -p "$verify_project" -f "$compose_file" ps -q postgres)

verify_stage=runtime-readiness
ready=false
for attempt in $(seq 1 90); do
  api_health=$(docker inspect "$api_id" \
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  worker_state=$(docker inspect "$worker_id" --format '{{.State.Status}}' 2>/dev/null || true)
  if [ "$api_health" = healthy ] && [ "$worker_state" = running ]; then
    ready=true
    break
  fi
  if [ "$api_health" = unhealthy ] || [ "$worker_state" = exited ]; then
    break
  fi
  sleep 2
done
if [ "$ready" != true ]; then
  docker compose -p "$verify_project" -f "$compose_file" ps -a >&2
  echo "Compose runtime did not become healthy." >&2
  exit 1
fi

verify_stage=database-state
readiness=$(docker exec "$api_id" wget -qO- \
  http://localhost:8080/actuator/health/readiness | jq -er .status)
[ "$readiness" = UP ]

migrations=$(docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  "SELECT string_agg(version || ':' || success, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6');")
[ "$migrations" = '1:true,2:true,3:true,4:true,5:true,6:true' ]

session_tables=$(docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('spring_session','spring_session_attributes');")
[ "$session_tables" = 2 ]

admin_state=$(docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  "SELECT (COUNT(*) = 1) || ':' || COALESCE(bool_and(must_change_password), false) FROM app_user WHERE lower(username)=lower('admin');")
[ "$admin_state" = 'true:true' ]
admin_roles=$(docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  "SELECT COUNT(*) FROM app_user_role r JOIN app_user u ON u.id=r.user_id WHERE lower(u.username)=lower('admin') AND r.role='ADMIN';")
[ "$admin_roles" = 1 ]

verify_stage=secure-session-login
csrf_json=$(curl -fsS -D "$csrf_headers" -c "$cookie_jar" \
  http://localhost:8088/api/v1/auth/csrf)
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)
rg -i --quiet '^set-cookie: XSRF-TOKEN=.*; Secure;' "$csrf_headers"
login_body=$(jq -cn '{username:env.KMP_BOOTSTRAP_ADMIN_USERNAME,password:env.KMP_BOOTSTRAP_ADMIN_PASSWORD}')
login_status=$(printf '%s' "$login_body" | curl -sS -D "$login_headers" \
  -o /dev/null -w '%{http_code}' -b "$cookie_jar" -c "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
  --data-binary @- http://localhost:8088/api/v1/auth/login)
[ "$login_status" = 204 ]
rg -i --quiet '^set-cookie: KMP_SESSION=.*; Secure;' "$login_headers"
session_rows=$(docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  'SELECT COUNT(*) FROM spring_session;')
[ "$session_rows" = 1 ]

verify_stage=worker-start-log
worker_started=false
for attempt in $(seq 1 60); do
  if docker logs "$worker_id" 2>&1 | rg --quiet 'Started WorkbenchWorkerApplication'; then
    worker_started=true
    break
  fi
  worker_state=$(docker inspect "$worker_id" --format '{{.State.Status}}')
  [ "$worker_state" != exited ] || break
  sleep 1
done
[ "$worker_started" = true ]
verify_stage=worker-file-log
docker exec "$worker_id" sh -c 'test ! -e /app/logs'
verify_stage=worker-flags
worker_flags=$(docker inspect "$worker_id" --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | rg '^(KMP_AGENT_ENABLED=false|SPRING_FLYWAY_ENABLED=false)$' | sort | paste -sd ',' -)
[ "$worker_flags" = 'KMP_AGENT_ENABLED=false,SPRING_FLYWAY_ENABLED=false' ]

verify_stage=host-port-isolation
api_ports=$(docker inspect "$api_id" --format '{{json .HostConfig.PortBindings}}')
worker_ports=$(docker inspect "$worker_id" --format '{{json .HostConfig.PortBindings}}')
[ "$api_ports" = '{}' ]
[ "$worker_ports" = '{}' ]
verify_stage=secret-mounts
docker exec "$api_id" sh -c \
  'test -s /run/secrets/workbench.bootstrap-admin.password && test -s /run/secrets/kmp_model_master_key'
docker exec "$worker_id" sh -c 'test -s /run/secrets/kmp_model_master_key'

verify_stage=secret-leak-check
for secret_value in "$KMP_DB_PASSWORD" "$KMP_BOOTSTRAP_ADMIN_PASSWORD" "$KMP_MODEL_MASTER_KEY"; do
  for container_id in "$api_id" "$worker_id"; do
    if docker logs "$container_id" 2>&1 | rg -F --quiet -- "$secret_value"; then
      echo "Secret value found in container logs." >&2
      exit 1
    fi
  done
done

printf 'RUNTIME_OK readiness=%s migrations=%s admin=%s session_tables=%s login=%s session_rows=%s secure_cookies=true secret_leak=false\n' \
  "$readiness" "$migrations" "$admin_state" "$session_tables" "$login_status" "$session_rows"

cleanup_runtime
trap - 0 1 2 15
containers_left=$(docker ps -a --filter label=com.docker.compose.project="$verify_project" -q | wc -l | tr -d ' ')
volumes_left=$(docker volume ls --filter label=com.docker.compose.project="$verify_project" -q | wc -l | tr -d ' ')
printf 'CLEANUP_OK containers=%s volumes=%s\n' "$containers_left" "$volumes_left"
[ "$containers_left" = 0 ]
[ "$volumes_left" = 0 ]
