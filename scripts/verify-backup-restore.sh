#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
compose_file="$repo_root/deploy/compose/compose.yaml"
override_file="$repo_root/deploy/compose/operations-override.yaml"
source_project=kmp-backup-source-check
target_project=kmp-backup-target-check
verify_tmp=$(mktemp -d "${TMPDIR:-/tmp}/kmp-backup-restore.XXXXXX")
backup_dir="$verify_tmp/backup"
scene_id=00000000-0000-4000-8000-000000000777
object_key=backup-restore-probe.txt
object_body=KMP_BACKUP_RESTORE_PROBE_2026
minio_image=quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z

compose_for() {
  project=$1
  shift
  docker compose -p "$project" -f "$compose_file" -f "$override_file" "$@"
}

finish_verify() {
  result=$?
  trap - 0 1 2 15
  compose_for "$source_project" down --volumes --remove-orphans >/dev/null 2>&1 || true
  compose_for "$target_project" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$verify_tmp"
  source_left=$(docker ps -a --filter label=com.docker.compose.project="$source_project" -q | wc -l | tr -d ' ')
  target_left=$(docker ps -a --filter label=com.docker.compose.project="$target_project" -q | wc -l | tr -d ' ')
  source_volumes=$(docker volume ls --filter label=com.docker.compose.project="$source_project" -q | wc -l | tr -d ' ')
  target_volumes=$(docker volume ls --filter label=com.docker.compose.project="$target_project" -q | wc -l | tr -d ' ')
  if [ "$source_left" = 0 ] && [ "$target_left" = 0 ] \
      && [ "$source_volumes" = 0 ] && [ "$target_volumes" = 0 ]; then
    printf 'CLEANUP_OK source_containers=0 target_containers=0 source_volumes=0 target_volumes=0\n'
  else
    printf 'CLEANUP_FAILED source_containers=%s target_containers=%s source_volumes=%s target_volumes=%s\n' \
      "$source_left" "$target_left" "$source_volumes" "$target_volumes" >&2
    [ "$result" -ne 0 ] || result=1
  fi
  exit "$result"
}
trap finish_verify 0
trap 'exit 130' 1 2 15

KMP_DB_NAME=kmp_backup_verify
KMP_DB_USER=kmp_backup
KMP_DB_PASSWORD=$(openssl rand -base64 24)
KMP_MINIO_ROOT_USER=kmp-backup
KMP_MINIO_ROOT_PASSWORD=$(openssl rand -base64 24)
KMP_BOOTSTRAP_ADMIN_USERNAME=admin
KMP_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 24)
KMP_MODEL_MASTER_KEY=$(openssl rand -base64 32)
KMP_SECURE_COOKIES=false
export KMP_DB_NAME KMP_DB_USER KMP_DB_PASSWORD
export KMP_MINIO_ROOT_USER KMP_MINIO_ROOT_PASSWORD
export KMP_BOOTSTRAP_ADMIN_USERNAME KMP_BOOTSTRAP_ADMIN_PASSWORD KMP_MODEL_MASTER_KEY KMP_SECURE_COOKIES

compose_for "$source_project" down --volumes --remove-orphans >/dev/null 2>&1 || true
compose_for "$target_project" down --volumes --remove-orphans >/dev/null 2>&1 || true
if [ "${KMP_OPERATIONS_BUILD:-true}" = true ]; then
  compose_for "$source_project" build api >/dev/null
fi
compose_for "$source_project" up -d --no-build api >/dev/null

source_api=$(compose_for "$source_project" ps -q api)
source_postgres=$(compose_for "$source_project" ps -q postgres)
ready=false
for attempt in $(seq 1 90); do
  health=$(docker inspect "$source_api" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  if [ "$health" = healthy ]; then ready=true; break; fi
  sleep 2
done
[ "$ready" = true ]

docker exec "$source_postgres" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -v ON_ERROR_STOP=1 -c \
  "INSERT INTO scene (id,name,description,created_at,updated_at) VALUES ('$scene_id','备份恢复验收','isolated verification',now(),now());" >/dev/null
printf '%s\n%s\n%s\n' "$KMP_MINIO_ROOT_USER" "$KMP_MINIO_ROOT_PASSWORD" "$object_body" | \
  docker run --rm -i --network "${source_project}_backend" --entrypoint /bin/sh "$minio_image" -eu -c '
    IFS= read -r minio_user
    IFS= read -r minio_password
    IFS= read -r object_body
    mc alias set target http://minio:9000 "$minio_user" "$minio_password" >/dev/null
    printf "%s" "$object_body" | mc pipe target/kmp-assets/backup-restore-probe.txt >/dev/null
  '

KMP_COMPOSE_PROJECT=$source_project \
KMP_COMPOSE_OVERRIDE=$override_file \
KMP_BACKUP_DIR=$backup_dir \
  "$repo_root/scripts/backup-compose.sh"

if KMP_COMPOSE_PROJECT=$source_project \
    KMP_COMPOSE_OVERRIDE=$override_file \
    KMP_BACKUP_DIR=$backup_dir \
    KMP_RESTORE_CONFIRM=RESTORE_EMPTY_PROJECT \
    KMP_RESTORE_START_APP=false \
    "$repo_root/scripts/restore-compose.sh" >/dev/null 2>&1; then
  echo "Restore unexpectedly accepted a non-empty project." >&2
  exit 1
fi
source_scene_count=$(docker exec "$source_postgres" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  "SELECT count(*) FROM scene WHERE id='$scene_id';")
[ "$source_scene_count" = 1 ]
printf 'RESTORE_GUARD_OK non_empty_project_rejected=true source_scene_intact=%s\n' "$source_scene_count"

compose_for "$source_project" down --volumes --remove-orphans >/dev/null

KMP_COMPOSE_PROJECT=$target_project \
KMP_COMPOSE_OVERRIDE=$override_file \
KMP_BACKUP_DIR=$backup_dir \
KMP_RESTORE_CONFIRM=RESTORE_EMPTY_PROJECT \
KMP_RESTORE_START_APP=false \
  "$repo_root/scripts/restore-compose.sh"

target_postgres=$(compose_for "$target_project" ps -q postgres)
scene_count=$(docker exec "$target_postgres" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  "SELECT count(*) FROM scene WHERE id='$scene_id' AND name='备份恢复验收';")
[ "$scene_count" = 1 ]
restored_object=$(printf '%s\n%s\n' "$KMP_MINIO_ROOT_USER" "$KMP_MINIO_ROOT_PASSWORD" | \
  docker run --rm -i --network "${target_project}_backend" --entrypoint /bin/sh "$minio_image" -eu -c '
    IFS= read -r minio_user
    IFS= read -r minio_password
    mc alias set target http://minio:9000 "$minio_user" "$minio_password" >/dev/null
    mc cat target/kmp-assets/backup-restore-probe.txt
  ')
[ "$restored_object" = "$object_body" ]

compose_for "$target_project" up -d --no-build api >/dev/null
target_api=$(compose_for "$target_project" ps -q api)
ready=false
for attempt in $(seq 1 90); do
  health=$(docker inspect "$target_api" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  if [ "$health" = healthy ]; then ready=true; break; fi
  sleep 2
done
[ "$ready" = true ]
readiness=$(docker exec "$target_api" wget -qO- http://localhost:8080/actuator/health/readiness | jq -er .status)
[ "$readiness" = UP ]

printf 'BACKUP_RESTORE_OK scene=%s object=%s readiness=%s checksums=verified\n' \
  "$scene_count" "$restored_object" "$readiness"
