#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
compose_file="$repo_root/deploy/compose/compose.yaml"
compose_project=${KMP_COMPOSE_PROJECT:?KMP_COMPOSE_PROJECT must name a new empty Compose project}
compose_override=${KMP_COMPOSE_OVERRIDE:-}
backup_dir=${KMP_BACKUP_DIR:?KMP_BACKUP_DIR must point to a verified backup}
restore_confirm=${KMP_RESTORE_CONFIRM:-}
restore_start_app=${KMP_RESTORE_START_APP:-true}
db_name=${KMP_DB_NAME:-knowledge_melting_pot}
db_user=${KMP_DB_USER:-kmp}
minio_user=${KMP_MINIO_ROOT_USER:-kmp-local}
minio_password=${KMP_MINIO_ROOT_PASSWORD:?KMP_MINIO_ROOT_PASSWORD is required}
minio_image=quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z

if [ "$restore_confirm" != RESTORE_EMPTY_PROJECT ]; then
  echo "Set KMP_RESTORE_CONFIRM=RESTORE_EMPTY_PROJECT after verifying the target project is disposable and empty." >&2
  exit 2
fi
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

for required in metadata.json postgres.dump objects.tar SHA256SUMS; do
  [ -s "$backup_dir/$required" ] || { echo "Missing backup component: $required" >&2; exit 1; }
done
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$backup_dir" && sha256sum -c SHA256SUMS)
else
  (cd "$backup_dir" && shasum -a 256 -c SHA256SUMS)
fi
format_version=$(jq -er '.formatVersion' "$backup_dir/metadata.json")
[ "$format_version" = 1 ] || { echo "Unsupported backup format version: $format_version" >&2; exit 1; }
if tar -tf "$backup_dir/objects.tar" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  echo "Object archive contains an unsafe path." >&2
  exit 1
fi
if tar -tvf "$backup_dir/objects.tar" | awk '$1 !~ /^[-d]/ { found=1 } END { exit !found }'; then
  echo "Object archive contains a non-regular entry." >&2
  exit 1
fi

containers=$(docker ps -a --filter label=com.docker.compose.project="$compose_project" -q)
volumes=$(docker volume ls --filter label=com.docker.compose.project="$compose_project" -q)
if [ -n "$containers" ] || [ -n "$volumes" ]; then
  echo "Restore target is not empty: $compose_project" >&2
  exit 2
fi

stage_dir=$(mktemp -d "${TMPDIR:-/tmp}/kmp-restore-stage.XXXXXX")
cleanup_stage() {
  result=$?
  trap - 0 1 2 15
  rm -rf "$stage_dir"
  exit "$result"
}
trap cleanup_stage 0
trap 'exit 130' 1 2 15
mkdir -p "$stage_dir/objects"
tar -C "$stage_dir/objects" -xf "$backup_dir/objects.tar"
expected_object_count=$(jq -er '.objectCount' "$backup_dir/metadata.json")
actual_object_count=$(find "$stage_dir/objects" -type f | wc -l | tr -d ' ')
[ "$actual_object_count" = "$expected_object_count" ] || {
  echo "Object count does not match backup metadata." >&2
  exit 1
}

compose up -d postgres minio >/dev/null
postgres_id=$(compose ps -q postgres)
minio_id=$(compose ps -q minio)
ready=false
for attempt in $(seq 1 90); do
  postgres_health=$(docker inspect "$postgres_id" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  minio_health=$(docker inspect "$minio_id" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  if [ "$postgres_health" = healthy ] && [ "$minio_health" = healthy ]; then
    ready=true
    break
  fi
  sleep 2
done
[ "$ready" = true ] || { echo "Restore dependencies did not become healthy." >&2; exit 1; }
compose up --no-deps minio-init >/dev/null

docker exec -i "$postgres_id" pg_restore -U "$db_user" -d "$db_name" \
  --no-owner --no-acl <"$backup_dir/postgres.dump"

printf '%s\n%s\n' "$minio_user" "$minio_password" | docker run --rm -i \
  --network "${compose_project}_backend" \
  --mount "type=bind,src=$stage_dir/objects,dst=/backup,readonly" \
  --entrypoint /bin/sh "$minio_image" -eu -c '
    IFS= read -r minio_user
    IFS= read -r minio_password
    mc alias set target http://minio:9000 "$minio_user" "$minio_password" >/dev/null
    for bucket in kmp-quarantine kmp-verified-knowledge kmp-verified-holdout kmp-assets; do
      mc mirror --overwrite --preserve "/backup/$bucket" "target/$bucket" >/dev/null
    done
  '

if [ "$restore_start_app" = true ]; then
  compose up -d --no-build api worker web >/dev/null
fi
object_count=$expected_object_count
printf 'RESTORE_OK project=%s objects=%s checksums=verified app_started=%s\n' \
  "$compose_project" "$object_count" "$restore_start_app"

trap - 0 1 2 15
rm -rf "$stage_dir"
