#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
verify_id="kmp-vector-$PPID-$$"
postgres_name="$verify_id-postgres"
runtime_tmp=$(mktemp -d "${TMPDIR:-/tmp}/kmp-vector-check.XXXXXX")
api_log="$runtime_tmp/api.log"
cookie_jar="$runtime_tmp/cookies.txt"
profile_response="$runtime_tmp/profile-response.json"
api_pid=""
verify_stage=initialization

cleanup() {
  result=$?
  trap - 0 1 2 15
  if [ -n "$api_pid" ] && kill -0 "$api_pid" 2>/dev/null; then
    kill "$api_pid" 2>/dev/null || true
    wait "$api_pid" 2>/dev/null || true
  fi
  docker rm -f "$postgres_name" >/dev/null 2>&1 || true
  if [ "$result" -ne 0 ]; then
    echo "VECTOR_RETRIEVAL_FAILED stage=$verify_stage" >&2
    tail -120 "$api_log" >&2 2>/dev/null || true
  fi
  for file in "$api_log" "$cookie_jar" "$profile_response"; do
    if [ -f "$file" ]; then
      : >"$file"
      rm "$file"
    fi
  done
  rmdir "$runtime_tmp" 2>/dev/null || true
  exit "$result"
}
trap cleanup 0
trap 'exit 130' 1 2 15

if [ "${KMP_VECTOR_BUILD:-true}" = true ]; then
  verify_stage=api-build
  "$repo_root/mvnw" -f "$repo_root/backend/pom.xml" -pl workbench-api -am package -DskipTests >/dev/null
fi
api_jar=$(ls "$repo_root"/backend/workbench-api/target/workbench-api-*.jar 2>/dev/null | head -1)
[ -f "$api_jar" ]

db_name=kmp_vector_verify
db_user=kmp_vector
db_password=$(openssl rand -base64 24)
admin_password=$(openssl rand -base64 24)
changed_admin_password=$(openssl rand -base64 24)
master_key=$(openssl rand -base64 32)
api_port=${KMP_VECTOR_API_PORT:-18089}

verify_stage=postgres-start
docker run -d --name "$postgres_name" \
  -e POSTGRES_DB="$db_name" -e POSTGRES_USER="$db_user" -e POSTGRES_PASSWORD="$db_password" \
  -p 127.0.0.1::5432 pgvector/pgvector:pg17 >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$postgres_name" pg_isready -U "$db_user" -d "$db_name" >/dev/null 2>&1; then
    break
  fi
  [ "$attempt" -lt 60 ] || exit 1
  sleep 1
done
db_port=$(docker port "$postgres_name" 5432/tcp | awk -F: 'NR == 1 {print $NF}')
[ -n "$db_port" ]

verify_stage=api-start
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$db_port/$db_name" \
SPRING_DATASOURCE_USERNAME="$db_user" \
SPRING_DATASOURCE_PASSWORD="$db_password" \
KMP_BOOTSTRAP_ADMIN_USERNAME=admin \
KMP_BOOTSTRAP_ADMIN_PASSWORD="$admin_password" \
KMP_MODEL_MASTER_KEY="$master_key" \
KMP_ALLOWED_MODEL_HOSTS=api.example.com \
KMP_SECURE_COOKIES=false \
SERVER_PORT="$api_port" \
java -jar "$api_jar" >"$api_log" 2>&1 &
api_pid=$!
api_base="http://127.0.0.1:$api_port"
for attempt in $(seq 1 90); do
  if curl -fsS "$api_base/actuator/health/readiness" 2>/dev/null | jq -e '.status == "UP"' >/dev/null 2>&1; then
    break
  fi
  kill -0 "$api_pid" 2>/dev/null || exit 1
  [ "$attempt" -lt 90 ] || exit 1
  sleep 1
done

verify_stage=migrations
migrations=$(docker exec "$postgres_name" psql -U "$db_user" -d "$db_name" -Atc \
  "SELECT string_agg(version || ':' || success, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::integer BETWEEN 1 AND 16;")
[ "$migrations" = '1:true,2:true,3:true,4:true,5:true,6:true,7:true,8:true,9:true,10:true,11:true,12:true,13:true,14:true,15:true,16:true' ]

verify_stage=verified-connection-fixture
connection_id=10000000-0000-4000-8000-000000000001
admin_id=$(docker exec "$postgres_name" psql -U "$db_user" -d "$db_name" -Atc \
  "SELECT id FROM app_user WHERE lower(username)='admin';")
[ -n "$admin_id" ]
docker exec -i "$postgres_name" psql -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name" >/dev/null <<SQL
INSERT INTO model_connection (
  id, name, provider, base_url, credential_envelope, enabled, validation_status,
  last_validated_at, next_config_version, created_by, created_at, updated_at)
VALUES (
  '$connection_id'::uuid, 'vector-runtime-fixture', 'DASHSCOPE',
  'https://api.example.com/api/v1', 'kmp1.runtime-fixture', TRUE, 'CONNECTIVITY_VERIFIED',
  NOW(), 1, '$admin_id'::uuid, NOW(), NOW());
SQL

verify_stage=profile-api
csrf_json=$(curl -fsS -c "$cookie_jar" "$api_base/api/v1/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)
login_body=$(printf '%s' "$admin_password" | jq -Rsc '{username:"admin",password:.}')
login_status=$(printf '%s' "$login_body" | curl -sS -o /dev/null -w '%{http_code}' \
  -b "$cookie_jar" -c "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H 'Content-Type: application/json' --data-binary @- "$api_base/api/v1/auth/login")
login_body=
[ "$login_status" = 204 ]
# Spring rotates the CSRF token after authentication; never reuse the anonymous token.
csrf_json=$(curl -fsS -b "$cookie_jar" -c "$cookie_jar" "$api_base/api/v1/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)
# Complete the mandatory first-password change through the public flow. The
# endpoint revokes the bootstrap session, so authenticate once more afterwards.
password_change_body=$(jq -cn --arg current "$admin_password" --arg replacement "$changed_admin_password" \
  '{currentPassword:$current,newPassword:$replacement}')
password_change_status=$(printf '%s' "$password_change_body" | curl -sS -o /dev/null -w '%{http_code}' \
  -b "$cookie_jar" -c "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H 'Content-Type: application/json' --data-binary @- "$api_base/api/v1/auth/password")
password_change_body=
[ "$password_change_status" = 204 ]
csrf_json=$(curl -fsS -b "$cookie_jar" -c "$cookie_jar" "$api_base/api/v1/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)
login_body=$(printf '%s' "$changed_admin_password" | jq -Rsc '{username:"admin",password:.}')
login_status=$(printf '%s' "$login_body" | curl -sS -o /dev/null -w '%{http_code}' \
  -b "$cookie_jar" -c "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H 'Content-Type: application/json' --data-binary @- "$api_base/api/v1/auth/login")
login_body=
[ "$login_status" = 204 ]
csrf_json=$(curl -fsS -b "$cookie_jar" -c "$cookie_jar" "$api_base/api/v1/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)
me_json=$(curl -fsS -b "$cookie_jar" "$api_base/api/v1/auth/me")
if ! printf '%s' "$me_json" | jq -e \
    '.mustChangePassword == false and (.roles | index("ADMIN") != null)' >/dev/null; then
  printf '%s' "$me_json" | jq '{mustChangePassword,roles}' >&2
  exit 1
fi
connections_json=$(curl -fsS -b "$cookie_jar" "$api_base/api/v1/model-connections")
if ! printf '%s' "$connections_json" | jq -e --arg id "$connection_id" \
    'any(.[]; .id == $id and .validationStatus == "CONNECTIVITY_VERIFIED")' >/dev/null; then
  printf '%s' "$connections_json" | jq '[.[] | {id,validationStatus}]' >&2
  exit 1
fi
profile_body=$(jq -cn --arg connectionId "$connection_id" '{
  modelConnectionId:$connectionId, modelId:"text-embedding-runtime", dimension:3,
  profileVersion:"runtime-v1", normalization:"L2", distanceFunction:"COSINE"}')
profile_status=$(printf '%s' "$profile_body" | curl -sS -o "$profile_response" -w '%{http_code}' -b "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
  --data-binary @- "$api_base/api/v1/embedding-profiles")
[ "$profile_status" = 201 ] || {
  printf '%s' "$profile_body" | jq '{modelConnectionId,modelId,dimension}' >&2
  jq '{status,code,detail,traceId}' "$profile_response" >&2 2>/dev/null || true
  exit 1
}
profile_json=$(cat "$profile_response")
profile_id=$(printf '%s' "$profile_json" | jq -er 'select(.active == true and .dimension == 3) | .id')
[ -n "$profile_id" ]

verify_stage=chinese-fixtures
scene_id=20000000-0000-4000-8000-000000000001
subscene_id=30000000-0000-4000-8000-000000000001
round_id=40000000-0000-4000-8000-000000000001
source_blob=50000000-0000-4000-8000-000000000001
holdout_blob=50000000-0000-4000-8000-000000000002
source_material=60000000-0000-4000-8000-000000000001
holdout_material=60000000-0000-4000-8000-000000000002
source_binding=70000000-0000-4000-8000-000000000001
holdout_binding=70000000-0000-4000-8000-000000000002
risk_chunk=80000000-0000-4000-8000-000000000001
routine_chunk=80000000-0000-4000-8000-000000000002
holdout_chunk=80000000-0000-4000-8000-000000000003
docker exec -i "$postgres_name" psql -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name" >/dev/null <<SQL
INSERT INTO scene (id, name, description, created_at, updated_at)
VALUES ('$scene_id', '中文向量验证', '', NOW(), NOW());
INSERT INTO sub_scene (id, scene_id, name, description, created_at, updated_at)
VALUES ('$subscene_id', '$scene_id', '逾期风险研判', '', NOW(), NOW());
INSERT INTO extraction_round (id, sub_scene_id, round_number, status, created_at, updated_at)
VALUES ('$round_id', '$subscene_id', 1, 'READY', NOW(), NOW());
INSERT INTO material_blob (id, security_partition, verified_sha256, clean_object_key, size_bytes,
  detected_mime, scan_engine_version, scan_signature_version, parser_name, parser_version, created_at)
VALUES
  ('$source_blob', 'KNOWLEDGE', repeat('a',64), 'knowledge/source.txt', 100, 'text/plain', 'runtime', 'runtime', 'txt', 'v1', NOW()),
  ('$holdout_blob', 'HOLDOUT', repeat('b',64), 'holdout/holdout.txt', 100, 'text/plain', 'runtime', 'runtime', 'txt', 'v1', NOW());
INSERT INTO material (id, file_name, file_format, media_type, object_key, sha256, size_bytes, status, blob_id, created_at, updated_at)
VALUES
  ('$source_material', 'source.txt', 'TXT', 'text/plain', 'knowledge/source.txt', repeat('a',64), 100, 'READY', '$source_blob', NOW(), NOW()),
  ('$holdout_material', 'holdout.txt', 'TXT', 'text/plain', 'holdout/holdout.txt', repeat('b',64), 100, 'READY', '$holdout_blob', NOW(), NOW());
INSERT INTO round_material (id, material_id, round_id, sub_scene_id, partition, share_scope, regulatory_source, active, created_at)
VALUES
  ('$source_binding', '$source_material', '$round_id', '$subscene_id', 'SOURCE', 'ROUND', FALSE, TRUE, NOW()),
  ('$holdout_binding', '$holdout_material', '$round_id', '$subscene_id', 'LABELED_HOLDOUT', 'ROUND', FALSE, TRUE, NOW());
INSERT INTO material_chunk (id, blob_id, ordinal, source_ref_code, locator, content, content_hash, char_count, parser_version, created_at)
VALUES
  ('$risk_chunk', '$source_blob', 0, 'SRC-KNOWLEDGE-RISK', '{"type":"TXT_LINES","lineStart":1,"lineEnd":3}', '逾期超过三十天时进入重点复核。', repeat('c',64), 16, 'v1', NOW()),
  ('$routine_chunk', '$source_blob', 1, 'SRC-KNOWLEDGE-ROUTINE', '{"type":"TXT_LINES","lineStart":4,"lineEnd":5}', '资料齐全时进入常规归档流程。', repeat('d',64), 14, 'v1', NOW()),
  ('$holdout_chunk', '$holdout_blob', 0, 'SRC-HOLDOUT-EXACT', '{"type":"TXT_LINES","lineStart":1,"lineEnd":2}', '留出集中的精确答案不得参与检索。', repeat('e',64), 16, 'v1', NOW());
INSERT INTO chunk_embedding (chunk_id, profile_version_id, content_hash, dimension, vector, created_at)
VALUES
  ('$risk_chunk', '$profile_id', repeat('c',64), 3, '[0.98,0.2,0]', NOW()),
  ('$routine_chunk', '$profile_id', repeat('d',64), 3, '[0,1,0]', NOW()),
  ('$holdout_chunk', '$profile_id', repeat('e',64), 3, '[1,0,0]', NOW());
SQL

verify_stage=retrieval-isolation
ranking=$(docker exec "$postgres_name" psql -U "$db_user" -d "$db_name" -Atc "
WITH eligible AS (
  SELECT DISTINCT ON (mc.id) mc.id, mc.source_ref_code
  FROM round_material rm
  JOIN material m ON m.id=rm.material_id
  JOIN material_chunk mc ON mc.blob_id=m.blob_id
  WHERE m.status='READY' AND rm.active=TRUE
    AND rm.partition IN ('SOURCE','LABELED_TRAIN')
    AND rm.round_id='$round_id'::uuid AND rm.sub_scene_id='$subscene_id'::uuid
  ORDER BY mc.id, rm.created_at, m.id
), ranked AS (
  SELECT e.source_ref_code, ce.vector::vector(3) <=> '[1,0,0]'::vector(3) AS distance
  FROM eligible e JOIN chunk_embedding ce ON ce.chunk_id=e.id
  WHERE ce.profile_version_id='$profile_id'::uuid
)
SELECT string_agg(source_ref_code, ',' ORDER BY distance) FROM ranked;")
[ "$ranking" = 'SRC-KNOWLEDGE-RISK,SRC-KNOWLEDGE-ROUTINE' ]

verify_stage=hnsw-plan
compact_profile=$(printf '%s' "$profile_id" | tr -d '-')
index_name="ix_ce_hnsw_$compact_profile"
index_count=$(docker exec "$postgres_name" psql -U "$db_user" -d "$db_name" -Atc \
  "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname='$index_name';")
[ "$index_count" = 1 ]
plan=$(docker exec "$postgres_name" psql -U "$db_user" -d "$db_name" -Atc "
SET enable_seqscan=off;
EXPLAIN SELECT chunk_id FROM chunk_embedding
WHERE profile_version_id='$profile_id'::uuid
ORDER BY vector::vector(3) <=> '[1,0,0]'::vector(3) LIMIT 2;")
case "$plan" in
  *"$index_name"*) ;;
  *) echo "Expected HNSW index was not selected" >&2; exit 1 ;;
esac

echo "VECTOR_RETRIEVAL_OK migrations=16 profile_active=true dimension=3 hnsw_index=true ranking=$ranking holdout_excluded=true"
