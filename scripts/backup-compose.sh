#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
compose_file="$repo_root/deploy/compose/compose.yaml"
compose_project=${KMP_COMPOSE_PROJECT:-knowledge-melting-pot}
compose_override=${KMP_COMPOSE_OVERRIDE:-}
backup_dir=${KMP_BACKUP_DIR:?KMP_BACKUP_DIR must be an absolute path to a new backup directory}
db_name=${KMP_DB_NAME:-knowledge_melting_pot}
db_user=${KMP_DB_USER:-kmp}
minio_user=${KMP_MINIO_ROOT_USER:-kmp-local}
minio_password=${KMP_MINIO_ROOT_PASSWORD:?KMP_MINIO_ROOT_PASSWORD is required}
minio_image=quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z

case "$backup_dir" in
  /*) ;;
  *) echo "KMP_BACKUP_DIR must be absolute." >&2; exit 2 ;;
esac
if [ -L "$backup_dir" ]; then
  echo "Backup directory must not be a symbolic link." >&2
  exit 2
fi

compose() {
  if [ -n "$compose_override" ]; then
    docker compose -p "$compose_project" -f "$compose_file" -f "$compose_override" "$@"
  else
    docker compose -p "$compose_project" -f "$compose_file" "$@"
  fi
}

if [ -e "$backup_dir" ] && [ -n "$(find "$backup_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  echo "Backup directory is not empty: $backup_dir" >&2
  exit 2
fi
umask 077
mkdir -p "$backup_dir"
stage_dir=$(mktemp -d "${TMPDIR:-/tmp}/kmp-backup-stage.XXXXXX")
running_services="$stage_dir/running-services"
: >"$running_services"

restart_services() {
  if [ -s "$running_services" ]; then
    while IFS= read -r service; do
      compose up -d --no-deps "$service" >/dev/null 2>&1 || true
    done <"$running_services"
  fi
}

cleanup_backup() {
  result=$?
  trap - 0 1 2 15
  restart_services
  if [ -d "$stage_dir" ]; then
    rm -rf "$stage_dir"
  fi
  exit "$result"
}
trap cleanup_backup 0
trap 'exit 130' 1 2 15

postgres_id=$(compose ps -q postgres)
minio_id=$(compose ps -q minio)
if [ -z "$postgres_id" ] || [ -z "$minio_id" ]; then
  echo "PostgreSQL and MinIO must be running before backup." >&2
  exit 1
fi

for service in web api worker evaluation-worker; do
  service_id=$(compose ps -q "$service" 2>/dev/null || true)
  if [ -n "$service_id" ] && [ "$(docker inspect "$service_id" --format '{{.State.Running}}')" = true ]; then
    printf '%s\n' "$service" >>"$running_services"
  fi
done
if [ -s "$running_services" ]; then
  while IFS= read -r service; do
    compose stop -t 60 "$service" >/dev/null
  done <"$running_services"
fi

docker exec -i "$postgres_id" pg_dump -U "$db_user" -d "$db_name" \
  --format=custom --no-owner --no-acl >"$backup_dir/postgres.dump"

mkdir -p "$stage_dir/objects"
printf '%s\n%s\n' "$minio_user" "$minio_password" | docker run --rm -i \
  --network "${compose_project}_backend" \
  --mount "type=bind,src=$stage_dir/objects,dst=/backup" \
  --entrypoint /bin/sh "$minio_image" -eu -c '
    IFS= read -r minio_user
    IFS= read -r minio_password
    mc alias set source http://minio:9000 "$minio_user" "$minio_password" >/dev/null
    for bucket in kmp-quarantine kmp-verified-knowledge kmp-verified-holdout kmp-assets; do
      mkdir -p "/backup/$bucket"
      mc mirror --overwrite --preserve "source/$bucket" "/backup/$bucket" >/dev/null
    done
  '
tar -C "$stage_dir/objects" -cf "$backup_dir/objects.tar" .

object_count=$(find "$stage_dir/objects" -type f | wc -l | tr -d ' ')
dump_bytes=$(wc -c <"$backup_dir/postgres.dump" | tr -d ' ')
created_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
jq -n \
  --arg formatVersion "1" \
  --arg createdAt "$created_at" \
  --arg project "$compose_project" \
  --arg database "$db_name" \
  --argjson objectCount "$object_count" \
  --argjson databaseBytes "$dump_bytes" \
  '{formatVersion:$formatVersion,createdAt:$createdAt,sourceProject:$project,database:$database,objectCount:$objectCount,databaseBytes:$databaseBytes}' \
  >"$backup_dir/metadata.json"

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$backup_dir" && sha256sum metadata.json postgres.dump objects.tar >SHA256SUMS)
else
  (cd "$backup_dir" && shasum -a 256 metadata.json postgres.dump objects.tar >SHA256SUMS)
fi
sync

printf 'BACKUP_OK directory=%s database_bytes=%s objects=%s services_quiesced=true\n' \
  "$backup_dir" "$dump_bytes" "$object_count"

restart_services
: >"$running_services"
trap - 0 1 2 15
rm -rf "$stage_dir"
