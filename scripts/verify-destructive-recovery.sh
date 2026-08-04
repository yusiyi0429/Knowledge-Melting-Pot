#!/bin/sh
set -eu

# Real destructive verification of Worker crash recovery and same-content
# concurrent deduplication, entirely inside the explicit compose project
# kmp-destructive-check. It never touches kmp-validation, kmp-runtime-check or
# any other stack. On exit it removes only its own containers and volumes.
#
# Scenarios:
#   RECOVERY - upload a TXT, let a single Worker begin INGEST, kill -9 that
#              Worker mid-processing, replace it with a fresh Worker and verify
#              the lease is re-claimed and the job completes with exactly one
#              verified blob and one chunk set (no duplicate blob/chunk).
#   DEDUP    - start a second Worker and upload TWO materials with byte-identical
#              content; both Workers ingest concurrently and must produce one
#              blob, one chunk set and one MinIO object for that content.
#   Embeddings are unconfigured (no real provider): the run must record the
#   stable EMBEDDING_PROVIDER_UNCONFIGURED diagnostic and write zero rows to
#   chunk_embedding instead of faking success.

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
compose_file="$repo_root/deploy/compose/compose.yaml"
override_file="$repo_root/deploy/compose/destructive-override.yaml"
project=kmp-destructive-check
runtime_tmp=$(mktemp -d "${TMPDIR:-/tmp}/kmp-destructive.XXXXXX")
cookie_jar="$runtime_tmp/cookies.txt"
csrf_headers="$runtime_tmp/csrf-headers.txt"
login_headers="$runtime_tmp/login-headers.txt"
recovery_payload="$runtime_tmp/recovery.txt"
dedup_payload="$runtime_tmp/dedup.txt"

cleanup_compose() {
  docker compose -p "$project" -f "$compose_file" -f "$override_file" \
    down --volumes --remove-orphans >/dev/null 2>&1 || true
}

cleanup_runtime() {
  cleanup_compose
  for temp_file in "$cookie_jar" "$csrf_headers" "$login_headers" \
    "$recovery_payload" "$dedup_payload"; do
    if [ -f "$temp_file" ]; then
      : >"$temp_file"
      rm -f "$temp_file"
    fi
  done
  rmdir "$runtime_tmp" 2>/dev/null || true
}

verify_stage=initialization
finish_runtime() {
  result=$?
  trap - 0 1 2 15
  if [ "$result" -ne 0 ]; then
    echo "DESTRUCTIVE_FAILED stage=$verify_stage" >&2
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
KMP_DB_NAME=${KMP_DB_NAME:-kmp_destructive_verify}
KMP_DB_USER=${KMP_DB_USER:-kmp_destructive}
KMP_BOOTSTRAP_ADMIN_USERNAME=admin
KMP_SECURE_COOKIES=true
KMP_WEB_PORT=${KMP_DESTRUCTIVE_WEB_PORT:-18089}
KMP_MINIO_PUBLIC_ENDPOINT=http://localhost:19000
KMP_MINIO_ROOT_USER=${KMP_MINIO_ROOT_USER:-kmp-local}
KMP_MINIO_ROOT_PASSWORD=${KMP_MINIO_ROOT_PASSWORD:-kmp-local-only}
web_base_url="http://localhost:${KMP_WEB_PORT}"
export KMP_DB_NAME KMP_DB_USER KMP_DB_PASSWORD KMP_WEB_PORT
export KMP_BOOTSTRAP_ADMIN_USERNAME KMP_BOOTSTRAP_ADMIN_PASSWORD
export KMP_MODEL_MASTER_KEY KMP_SECURE_COOKIES KMP_MINIO_PUBLIC_ENDPOINT
export KMP_MINIO_ROOT_USER KMP_MINIO_ROOT_PASSWORD

cleanup_compose
verify_stage=compose-build
docker compose -p "$project" -f "$compose_file" -f "$override_file" config --quiet
if [ "${KMP_DESTRUCTIVE_BUILD:-true}" = true ]; then
  docker compose -p "$project" -f "$compose_file" -f "$override_file" build api worker web worker-b
fi
docker compose -p "$project" -f "$compose_file" -f "$override_file" \
  up -d --no-build api worker web >/dev/null

api_id=$(docker compose -p "$project" -f "$compose_file" -f "$override_file" ps -q api)
postgres_id=$(docker compose -p "$project" -f "$compose_file" -f "$override_file" ps -q postgres)
minio_id=$(docker compose -p "$project" -f "$compose_file" -f "$override_file" ps -q minio)

psql_query() {
  docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc "$1"
}

verify_stage=runtime-readiness
ready=false
for attempt in $(seq 1 90); do
  api_health=$(docker inspect "$api_id" \
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  if [ "$api_health" = healthy ]; then
    ready=true
    break
  fi
  if [ "$api_health" = unhealthy ]; then
    break
  fi
  sleep 2
done
[ "$ready" = true ]

verify_stage=database-state
readiness=$(docker exec "$api_id" wget -qO- \
  http://localhost:8080/actuator/health/readiness | jq -er .status)
[ "$readiness" = UP ]
migrations=$(psql_query \
  "SELECT string_agg(version || ':' || success, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8','9','10','11','12','13');")
[ "$migrations" = '1:true,2:true,3:true,4:true,5:true,6:true,7:true,8:true,9:true,10:true,11:true,12:true,13:true' ]

verify_stage=secure-session-login
csrf_json=$(curl -fsS -D "$csrf_headers" -c "$cookie_jar" \
  "$web_base_url/api/v1/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)
login_body=$(jq -cn '{username:env.KMP_BOOTSTRAP_ADMIN_USERNAME,password:env.KMP_BOOTSTRAP_ADMIN_PASSWORD}')
login_status=$(printf '%s' "$login_body" | curl -sS -D "$login_headers" \
  -o /dev/null -w '%{http_code}' -b "$cookie_jar" -c "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
  --data-binary @- "$web_base_url/api/v1/auth/login")
[ "$login_status" = 204 ]

# First login requires a forced password change (V2 security flow); the change
# revokes the session, so log in again with the new password before using the
# workbench APIs.
KMP_WORKBENCH_PASSWORD=${KMP_WORKBENCH_PASSWORD:-$(openssl rand -base64 18)}
export KMP_WORKBENCH_PASSWORD
change_body=$(jq -cn --arg current "$KMP_BOOTSTRAP_ADMIN_PASSWORD" \
  --arg new "$KMP_WORKBENCH_PASSWORD" \
  '{currentPassword:$current,newPassword:$new}')
change_status=$(printf '%s' "$change_body" | curl -sS -o /dev/null -w '%{http_code}' \
  -b "$cookie_jar" -c "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
  --data-binary @- "$web_base_url/api/v1/auth/password")
[ "$change_status" = 204 ]
relogin_body=$(jq -cn '{username:env.KMP_BOOTSTRAP_ADMIN_USERNAME,password:env.KMP_WORKBENCH_PASSWORD}')
relogin_status=$(printf '%s' "$relogin_body" | curl -sS -D "$login_headers" \
  -o /dev/null -w '%{http_code}' -b "$cookie_jar" -c "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
  --data-binary @- "$web_base_url/api/v1/auth/login")
[ "$relogin_status" = 204 ]

api_request() {
  method=$1
  path=$2
  body=${3:-}
  if [ -n "$body" ]; then
    printf '%s' "$body" | curl -sS -b "$cookie_jar" \
      -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
      -X "$method" --data-binary @- "$web_base_url$path"
  else
    curl -sS -b "$cookie_jar" -H "$csrf_header: $csrf_token" \
      -X "$method" "$web_base_url$path"
  fi
}

verify_stage=scene-setup
scene_id=$(api_request POST /api/v1/scenes \
  '{"name":"destructive-check","description":"isolated destructive verification"}' | jq -er .id)
sub_scene_id=$(api_request POST "/api/v1/scenes/$scene_id/subscenes" \
  '{"name":"destructive-sub","description":""}' | jq -er .id)
round_id=$(api_request POST "/api/v1/scenes/$scene_id/rounds" \
  "{\"subSceneId\":\"$sub_scene_id\"}" | jq -er .id)

generate_payload() {
  file=$1
  lines=$2
  prefix=$3
  awk -v n="$lines" -v p="$prefix" 'BEGIN {
    pad = "padding-padding-padding-padding-padding-padding-padding-padding-padding"
    for (i = 1; i <= n; i++)
      printf "%s-line-%06d %s%s", p, i, pad, (i == n ? "" : "\n")
  }' >"$file"
}

assert_eq() {
  name=$1
  actual=$2
  expected=$3
  [ "$actual" = "$expected" ] || {
    echo "ASSERT_FAILED $name actual=$actual expected=$expected" >&2
    return 1
  }
}

upload_material() {
  file=$1
  name=$2
  size_bytes=$(wc -c <"$file" | tr -d ' ')
  sha256=$(shasum -a 256 "$file" | awk '{print $1}')
  intent_body="{\"fileName\":\"$name\",\"sizeBytes\":$size_bytes,\"mediaType\":\"text/plain\",\"sha256\":\"$sha256\",\"roundId\":\"$round_id\",\"subSceneIds\":[\"$sub_scene_id\"],\"partition\":\"SOURCE\",\"shareScope\":\"ROUND\",\"regulatorySource\":false}"
  intent_json=$(api_request POST /api/v1/materials/upload-intents "$intent_body")
  intent_id=$(printf '%s' "$intent_json" | jq -er .id)
  part_count=$(printf '%s' "$intent_json" | jq -er '.partCount')
  [ "$part_count" = 1 ] || {
    echo "Unexpected multipart intent (partCount=$part_count); keep the destructive fixture below one part." >&2
    return 1
  }
  part_url=$(printf '%s' "$intent_json" | jq -er '.parts[0].url')
  part_etag=$(curl -fsS -D - -o /dev/null -X PUT -T "$file" "$part_url" \
    | tr -d '\r' | sed -n 's/^[Ee][Tt][Aa][Gg]: *//p' | tr -d '"' | head -1)
  [ -n "$part_etag" ]
  complete_body="{\"parts\":[{\"partNumber\":1,\"etag\":\"$part_etag\"}]}"
  complete_json=$(api_request POST "/api/v1/materials/upload-intents/$intent_id/complete" \
    "$complete_body")
  printf '%s' "$complete_json" | jq -er '.jobId'
}

job_status() {
  api_request GET "/api/v1/jobs/$1" | jq -er .status
}

wait_job_terminal() {
  job_id=$1
  for attempt in $(seq 1 240); do
    status=$(job_status "$job_id")
    case "$status" in
      SUCCEEDED|FAILED|CANCELLED) printf '%s' "$status"; return 0 ;;
    esac
    sleep 1
  done
  return 1
}

worker_container() {
  docker compose -p "$project" -f "$compose_file" -f "$override_file" ps -q worker
}

# --- Scenario 1: kill -9 recovery -------------------------------------------
verify_stage=recovery-ingest-start
generate_payload "$recovery_payload" 70000 "recovery"
recovery_sha=$(shasum -a 256 "$recovery_payload" | awk '{print $1}')
recovery_lines=$(grep -c '^' "$recovery_payload")
recovery_chunks=$(((recovery_lines + 99) / 100))
recovery_job=$(upload_material "$recovery_payload" "recovery.txt")

verify_stage=recovery-kill
worker_id=$(worker_container)
killed=false
for attempt in $(seq 1 900); do
  stage=$(psql_query \
    "SELECT stage FROM material_ingest_attempt WHERE job_id='$recovery_job'")
  status=$(job_status "$recovery_job")
  if [ "$stage" != "" ] && [ "$stage" != "STARTED" ] && [ "$status" = RUNNING ]; then
    docker kill --signal=KILL "$worker_id" >/dev/null
    docker rm -f "$worker_id" >/dev/null 2>&1 || true
    killed=true
    break
  fi
  if [ "$status" = SUCCEEDED ] || [ "$status" = FAILED ]; then
    break
  fi
  sleep 0.1
done
[ "$killed" = true ]

verify_stage=recovery-reclaim
docker compose -p "$project" -f "$compose_file" -f "$override_file" \
  up -d --no-build worker >/dev/null
recovery_status=$(wait_job_terminal "$recovery_job")
[ "$recovery_status" = SUCCEEDED ]
recovery_attempt=$(psql_query "SELECT attempt FROM job WHERE id='$recovery_job';")
assert_eq recovery_attempt "$recovery_attempt" 2
recovery_started_events=$(psql_query \
  "SELECT COUNT(*) FROM job_event WHERE job_id='$recovery_job' AND event_type='started';")
assert_eq recovery_started_events "$recovery_started_events" 2

verify_stage=recovery-db-consistency
recovery_blobs=$(psql_query \
  "SELECT COUNT(*) FROM material_blob WHERE verified_sha256='$recovery_sha';")
assert_eq recovery_blobs "$recovery_blobs" 1
recovery_chunk_rows=$(psql_query \
  "SELECT COUNT(*) FROM material_chunk mc JOIN material_blob mb ON mb.id=mc.blob_id
   WHERE mb.verified_sha256='$recovery_sha';")
assert_eq recovery_chunks "$recovery_chunk_rows" "$recovery_chunks"
recovery_material_state=$(psql_query \
  "SELECT COUNT(*) FROM material m JOIN material_blob mb ON mb.id=m.blob_id
   WHERE mb.verified_sha256='$recovery_sha' AND m.status='READY';")
assert_eq recovery_material "$recovery_material_state" 1
recovery_objects=$(docker exec "$minio_id" sh -c \
  "mc alias set local http://127.0.0.1:9000 \"$KMP_MINIO_ROOT_USER\" \"$KMP_MINIO_ROOT_PASSWORD\" >/dev/null 2>&1 \
   && mc find local/kmp-verified-knowledge --name \"*$recovery_sha.txt\" | wc -l")
assert_eq recovery_minio_objects "$(printf '%s' "$recovery_objects" | tr -d ' ')" 1
echo "RECOVERY_OK kill=kill-9 stage=$stage reclaimed=true attempt=$recovery_attempt started_events=$recovery_started_events blobs=$recovery_blobs chunks=$recovery_chunk_rows objects=$recovery_objects"

# --- Scenario 2: two Workers, byte-identical content ------------------------
verify_stage=dedup-worker-b
docker compose -p "$project" -f "$compose_file" -f "$override_file" \
  up -d --no-build worker-b >/dev/null
for attempt in $(seq 1 90); do
  worker_b_state=$(docker inspect \
    "$(docker compose -p "$project" -f "$compose_file" -f "$override_file" ps -q worker-b)" \
    --format '{{.State.Status}}' 2>/dev/null || true)
  [ "$worker_b_state" = running ] && break
  sleep 2
done
[ "$worker_b_state" = running ]

verify_stage=dedup-ingest-start
generate_payload "$dedup_payload" 70000 "dedup"
dedup_sha=$(shasum -a 256 "$dedup_payload" | awk '{print $1}')
dedup_lines=$(grep -c '^' "$dedup_payload")
dedup_chunks=$(((dedup_lines + 99) / 100))
dedup_job_a=$(upload_material "$dedup_payload" "dedup-a.txt")
dedup_job_b=$(upload_material "$dedup_payload" "dedup-b.txt")

verify_stage=dedup-wait
status_a=$(wait_job_terminal "$dedup_job_a")
status_b=$(wait_job_terminal "$dedup_job_b")
assert_eq dedup_job_a_status "$status_a" SUCCEEDED
assert_eq dedup_job_b_status "$status_b" SUCCEEDED
dedup_workers=$(psql_query \
  "SELECT COUNT(DISTINCT payload->>'worker') FROM job_event
   WHERE job_id IN ('$dedup_job_a','$dedup_job_b') AND event_type='started';")
assert_eq dedup_distinct_workers "$dedup_workers" 2

verify_stage=dedup-db-consistency
dedup_blobs=$(psql_query \
  "SELECT COUNT(*) FROM material_blob WHERE verified_sha256='$dedup_sha';")
assert_eq dedup_blobs "$dedup_blobs" 1
dedup_chunk_rows=$(psql_query \
  "SELECT COUNT(*) FROM material_chunk mc JOIN material_blob mb ON mb.id=mc.blob_id
   WHERE mb.verified_sha256='$dedup_sha';")
assert_eq dedup_chunks "$dedup_chunk_rows" "$dedup_chunks"
dedup_shared_blob=$(psql_query \
  "SELECT COUNT(DISTINCT m.blob_id) FROM material m JOIN material_blob mb ON mb.id=m.blob_id
   WHERE mb.verified_sha256='$dedup_sha' AND m.status='READY';")
assert_eq dedup_shared_blob "$dedup_shared_blob" 1
dedup_materials_ready=$(psql_query \
  "SELECT COUNT(*) FROM material m JOIN material_blob mb ON mb.id=m.blob_id
   WHERE mb.verified_sha256='$dedup_sha' AND m.status='READY';")
assert_eq dedup_materials_ready "$dedup_materials_ready" 2
dedup_objects=$(docker exec "$minio_id" sh -c \
  "mc alias set local http://127.0.0.1:9000 \"$KMP_MINIO_ROOT_USER\" \"$KMP_MINIO_ROOT_PASSWORD\" >/dev/null 2>&1 \
   && mc find local/kmp-verified-knowledge --name \"*$dedup_sha.txt\" | wc -l")
assert_eq dedup_minio_objects "$(printf '%s' "$dedup_objects" | tr -d ' ')" 1
echo "DEDUP_OK workers=$dedup_workers blobs=$dedup_blobs chunks=$dedup_chunk_rows shared_blob=$dedup_shared_blob ready_materials=$dedup_materials_ready objects=$dedup_objects"

# --- Embedding provider unconfigured diagnostic ------------------------------
verify_stage=embedding-diagnostic
embedding_events=$(psql_query \
  "SELECT COUNT(*) FROM job_event WHERE payload::text LIKE '%EMBEDDING_PROVIDER_UNCONFIGURED%';")
[ "$embedding_events" -ge 1 ] || {
  echo "ASSERT_FAILED embedding_diagnostic_events actual=$embedding_events expected>=1" >&2
  exit 1
}
embedding_rows=$(psql_query "SELECT COUNT(*) FROM chunk_embedding;")
assert_eq chunk_embedding_rows "$embedding_rows" 0
fake_committed=$(psql_query \
  "SELECT COUNT(*) FROM job_event WHERE payload::text LIKE '%EMBEDDINGS_COMMITTED%';")
assert_eq faked_embeddings_committed "$fake_committed" 0
echo "EMBEDDING_UNCONFIGURED_OK diagnostic_events=$embedding_events chunk_embedding_rows=$embedding_rows faked_committed=$fake_committed"

# --- Secret leak check -------------------------------------------------------
verify_stage=secret-leak-check
all_worker_ids=$(docker compose -p "$project" -f "$compose_file" -f "$override_file" \
  ps -q worker worker-b 2>/dev/null || true)
for secret_value in "$KMP_DB_PASSWORD" "$KMP_BOOTSTRAP_ADMIN_PASSWORD" "$KMP_WORKBENCH_PASSWORD" \
  "$KMP_MODEL_MASTER_KEY"; do
  for container_id in "$api_id" $all_worker_ids; do
    [ -n "$container_id" ] || continue
    if docker logs "$container_id" 2>&1 | rg -F --quiet -- "$secret_value"; then
      echo "Secret value found in container logs." >&2
      exit 1
    fi
  done
done

echo "DESTRUCTIVE_OK recovery=$recovery_status dedup_a=$status_a dedup_b=$status_b migrations=$migrations"

cleanup_runtime
trap - 0 1 2 15
containers_left=$(docker ps -a --filter label=com.docker.compose.project="$project" -q | wc -l | tr -d ' ')
volumes_left=$(docker volume ls --filter label=com.docker.compose.project="$project" -q | wc -l | tr -d ' ')
printf 'CLEANUP_OK containers=%s volumes=%s\n' "$containers_left" "$volumes_left"
[ "$containers_left" = 0 ]
[ "$volumes_left" = 0 ]
