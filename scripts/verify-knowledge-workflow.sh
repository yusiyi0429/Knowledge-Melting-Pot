#!/bin/sh
set -eu

# Real local Compose acceptance for the KnowledgeIR/Markdown/Map-Reduce/alignment
# milestone. External model calls are deliberately replaced by the explicit,
# opt-in deterministic Worker adapter. The production Agent adapter remains off.

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
compose_file="$repo_root/deploy/compose/compose.yaml"
override_file="$repo_root/deploy/compose/knowledge-workflow-override.yaml"
project=kmp-knowledge-check
runtime_tmp=$(mktemp -d "${TMPDIR:-/tmp}/kmp-knowledge.XXXXXX")
cookie_jar="$runtime_tmp/cookies.txt"
headers_file="$runtime_tmp/headers.txt"
sample_file="$runtime_tmp/regulatory-sample.txt"
problem_file="$runtime_tmp/problem.json"

cleanup_compose() {
  docker compose -p "$project" -f "$compose_file" -f "$override_file" \
    down --volumes --remove-orphans >/dev/null 2>&1 || true
}

verify_stage=initialization
finish_runtime() {
  result=$?
  trap - 0 1 2 15
  if [ "$result" -ne 0 ]; then
    echo "KNOWLEDGE_WORKFLOW_FAILED stage=$verify_stage" >&2
    api_failure_id=$(docker compose -p "$project" -f "$compose_file" -f "$override_file" ps -q api 2>/dev/null || true)
    if [ -n "$api_failure_id" ]; then
      docker logs --tail 400 "$api_failure_id" 2>&1 | tail -400 >&2 || true
    fi
  fi
  cleanup_compose
  for file in "$cookie_jar" "$headers_file" "$sample_file" "$problem_file"; do
    if [ -f "$file" ]; then
      : >"$file"
      rm -f "$file"
    fi
  done
  rmdir "$runtime_tmp" 2>/dev/null || true
  exit "$result"
}
trap finish_runtime 0
trap 'exit 130' 1 2 15

KMP_DB_NAME=${KMP_DB_NAME:-kmp_knowledge_verify}
KMP_DB_USER=${KMP_DB_USER:-kmp_knowledge}
KMP_DB_PASSWORD=${KMP_DB_PASSWORD:-$(openssl rand -base64 24)}
KMP_BOOTSTRAP_ADMIN_USERNAME=admin
KMP_BOOTSTRAP_ADMIN_PASSWORD=${KMP_BOOTSTRAP_ADMIN_PASSWORD:-$(openssl rand -base64 24)}
KMP_WORKBENCH_PASSWORD=${KMP_WORKBENCH_PASSWORD:-$(openssl rand -base64 18)}
KMP_MODEL_MASTER_KEY=${KMP_MODEL_MASTER_KEY:-$(openssl rand -base64 32)}
KMP_SECURE_COOKIES=true
KMP_WEB_PORT=${KMP_KNOWLEDGE_WEB_PORT:-18090}
KMP_MINIO_PUBLIC_ENDPOINT=http://localhost:19100
KMP_MINIO_ROOT_USER=${KMP_MINIO_ROOT_USER:-kmp-local}
KMP_MINIO_ROOT_PASSWORD=${KMP_MINIO_ROOT_PASSWORD:-kmp-local-only}
web_base_url="http://localhost:${KMP_WEB_PORT}"
export KMP_DB_NAME KMP_DB_USER KMP_DB_PASSWORD KMP_WEB_PORT
export KMP_BOOTSTRAP_ADMIN_USERNAME KMP_BOOTSTRAP_ADMIN_PASSWORD KMP_WORKBENCH_PASSWORD
export KMP_MODEL_MASTER_KEY KMP_SECURE_COOKIES KMP_MINIO_PUBLIC_ENDPOINT
export KMP_MINIO_ROOT_USER KMP_MINIO_ROOT_PASSWORD

cleanup_compose
verify_stage=compose-build
docker compose -p "$project" -f "$compose_file" -f "$override_file" config --quiet
if [ "${KMP_KNOWLEDGE_BUILD:-true}" = true ]; then
  docker compose -p "$project" -f "$compose_file" -f "$override_file" build api worker web
fi
docker compose -p "$project" -f "$compose_file" -f "$override_file" \
  up -d --no-build api worker web >/dev/null

api_id=$(docker compose -p "$project" -f "$compose_file" -f "$override_file" ps -q api)
postgres_id=$(docker compose -p "$project" -f "$compose_file" -f "$override_file" ps -q postgres)

psql_query() {
  docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc "$1"
}

assert_equal() {
  actual=$1
  expected=$2
  label=$3
  if [ "$actual" != "$expected" ]; then
    echo "INVARIANT_FAILED $label expected=$expected actual=$actual" >&2
    return 1
  fi
}

assert_at_least() {
  actual=$1
  minimum=$2
  label=$3
  if [ "$actual" -lt "$minimum" ]; then
    echo "INVARIANT_FAILED $label minimum=$minimum actual=$actual" >&2
    return 1
  fi
}

verify_stage=runtime-readiness
ready=false
for attempt in $(seq 1 120); do
  api_health=$(docker inspect "$api_id" \
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  if [ "$api_health" = healthy ]; then
    ready=true
    break
  fi
  [ "$api_health" != unhealthy ] || break
  sleep 2
done
[ "$ready" = true ]

verify_stage=session-login
csrf_json=$(curl -fsS -c "$cookie_jar" "$web_base_url/api/v1/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)
login_body=$(jq -cn '{username:env.KMP_BOOTSTRAP_ADMIN_USERNAME,password:env.KMP_BOOTSTRAP_ADMIN_PASSWORD}')
login_status=$(printf '%s' "$login_body" | curl -sS -o /dev/null -w '%{http_code}' \
  -b "$cookie_jar" -c "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H 'Content-Type: application/json' --data-binary @- "$web_base_url/api/v1/auth/login")
[ "$login_status" = 204 ]
change_body=$(jq -cn --arg current "$KMP_BOOTSTRAP_ADMIN_PASSWORD" --arg new "$KMP_WORKBENCH_PASSWORD" \
  '{currentPassword:$current,newPassword:$new}')
change_status=$(printf '%s' "$change_body" | curl -sS -o /dev/null -w '%{http_code}' \
  -b "$cookie_jar" -c "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H 'Content-Type: application/json' --data-binary @- "$web_base_url/api/v1/auth/password")
[ "$change_status" = 204 ]
relogin_body=$(jq -cn '{username:env.KMP_BOOTSTRAP_ADMIN_USERNAME,password:env.KMP_WORKBENCH_PASSWORD}')
relogin_status=$(printf '%s' "$relogin_body" | curl -sS -o /dev/null -w '%{http_code}' \
  -b "$cookie_jar" -c "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H 'Content-Type: application/json' --data-binary @- "$web_base_url/api/v1/auth/login")
[ "$relogin_status" = 204 ]

api_request() {
  method=$1
  path=$2
  body=${3:-}
  idempotency_key=${4:-}
  if [ -n "$body" ]; then
    if [ -n "$idempotency_key" ]; then
      printf '%s' "$body" | curl -fsS -b "$cookie_jar" -H "$csrf_header: $csrf_token" \
        -H 'Content-Type: application/json' -H "Idempotency-Key: $idempotency_key" \
        -X "$method" --data-binary @- "$web_base_url$path"
    else
      printf '%s' "$body" | curl -fsS -b "$cookie_jar" -H "$csrf_header: $csrf_token" \
        -H 'Content-Type: application/json' -X "$method" --data-binary @- "$web_base_url$path"
    fi
  else
    curl -fsS -b "$cookie_jar" -H "$csrf_header: $csrf_token" -X "$method" "$web_base_url$path"
  fi
}

job_status() {
  api_request GET "/api/v1/jobs/$1" | jq -er .status
}

wait_job_success() {
  job_id=$1
  for attempt in $(seq 1 240); do
    status=$(job_status "$job_id")
    case "$status" in
      SUCCEEDED) return 0 ;;
      FAILED|CANCELLED)
        api_request GET "/api/v1/jobs/$job_id" >&2
        return 1
        ;;
    esac
    sleep 1
  done
  return 1
}

verify_stage=domain-setup
scene_id=$(api_request POST /api/v1/scenes \
  '{"name":"knowledge-workflow-check","description":"isolated deterministic acceptance"}' | jq -er .id)
sub_scene_id=$(api_request POST "/api/v1/scenes/$scene_id/subscenes" \
  '{"name":"loan-regulatory-check","description":"traceable local fixture"}' | jq -er .id)
round_id=$(api_request POST "/api/v1/scenes/$scene_id/rounds" \
  "{\"subSceneId\":\"$sub_scene_id\"}" | jq -er .id)

verify_stage=model-connection-setup
model_connection=$(api_request POST /api/v1/model-connections \
  '{"name":"local-acceptance-model","provider":"OPENAI_COMPATIBLE","baseUrl":"https://api.openai.com/v1","credential":"local-test-placeholder","enabled":true}')
model_connection_id=$(printf '%s' "$model_connection" | jq -er .id)
verify_stage=model-version-setup
model_version_response=$(printf '%s' \
  '{"modelId":"deterministic-fixture","temperature":0.0,"maxOutputTokens":2048}' \
  | curl -sS -D "$headers_file" -b "$cookie_jar" -H "$csrf_header: $csrf_token" \
      -H 'Content-Type: application/json' -X POST --data-binary @- \
      "$web_base_url/api/v1/model-connections/$model_connection_id/config-versions")
model_version_status=$(awk 'toupper($1) ~ /^HTTP\// {status=$2} END {print status}' "$headers_file")
if [ "$model_version_status" != 201 ]; then
  printf '%s\n' "$model_version_response" >&2
  exit 1
fi
model_version_id=$(printf '%s' "$model_version_response" | jq -er .id)
verify_stage=skill-version-setup
skill_manifest='{"executionMode":"RESOURCE_ONLY","schemaVersion":"knowledge-extraction/v1","prompt":"Only use supplied sources"}'
skill_hash=$(printf '%s' "$skill_manifest" | shasum -a 256 | awk '{print $1}')
skill_body=$(jq -cn --arg manifest "$skill_manifest" --arg hash "$skill_hash" \
  '{name:"local-acceptance-skill",description:"resource-only deterministic fixture",manifest:$manifest,packageHash:$hash}')
skill_id=$(api_request POST /api/v1/skills "$skill_body" skill-create-check | jq -er .id)
skill_version_id=$(api_request GET "/api/v1/skills/$skill_id" | jq -er '.versions[0].id')

verify_stage=material-upload
printf '%s\n' \
  '贷款逾期超过九十天时，应至少纳入次级类管理。' \
  '若存在经审批的监管例外，应记录例外条件和审批依据。' \
  '每条分类结论必须保留原始制度来源和定位。' >"$sample_file"
size_bytes=$(wc -c <"$sample_file" | tr -d ' ')
sha256=$(shasum -a 256 "$sample_file" | awk '{print $1}')
intent_body=$(jq -cn --arg name regulatory-sample.txt --arg sha "$sha256" \
  --arg round "$round_id" --arg sub "$sub_scene_id" --argjson size "$size_bytes" \
  '{fileName:$name,sizeBytes:$size,mediaType:"text/plain",sha256:$sha,roundId:$round,subSceneIds:[$sub],partition:"SOURCE",shareScope:"ROUND",regulatorySource:true}')
intent_json=$(api_request POST /api/v1/materials/upload-intents "$intent_body" material-intent-check)
intent_id=$(printf '%s' "$intent_json" | jq -er .id)
material_id=$(printf '%s' "$intent_json" | jq -er .materialId)
part_url=$(printf '%s' "$intent_json" | jq -er '.parts[0].url')
part_etag=$(curl -fsS -D - -o /dev/null -X PUT -T "$sample_file" "$part_url" \
  | tr -d '\r' | sed -n 's/^[Ee][Tt][Aa][Gg]: *//p' | tr -d '"' | head -1)
[ -n "$part_etag" ]
complete_body=$(jq -cn --arg etag "$part_etag" '{parts:[{partNumber:1,etag:$etag}]}')
ingest_job=$(api_request POST "/api/v1/materials/upload-intents/$intent_id/complete" "$complete_body" | jq -er .jobId)
wait_job_success "$ingest_job"

verify_stage=map-reduce-extraction
extract_body=$(jq -cn --arg round "$round_id" --arg model "$model_version_id" --arg skill "$skill_version_id" \
  '{roundId:$round,modelConfigVersionId:$model,skillVersionId:$skill}')
extract_job=$(api_request POST "/api/v1/subscenes/$sub_scene_id/extraction-jobs" \
  "$extract_body" extraction-check | jq -er .jobId)
wait_job_success "$extract_job"
document_json=$(api_request GET "/api/v1/knowledge-documents/$sub_scene_id")
revision_id=$(printf '%s' "$document_json" | jq -er .revisionId)
base_etag=$(printf '%s' "$document_json" | jq -er .etag)
source_ref_count=$(printf '%s' "$document_json" | jq -er '.sourceRefs | length')
[ "$source_ref_count" -ge 1 ]
printf '%s' "$document_json" | jq -er '.contentMd | contains("```kmp-metadata") and contains("```kmp-rule") and contains("```kmp-source-ref")' >/dev/null

verify_stage=markdown-rejection
revision_count_before=$(api_request GET "/api/v1/knowledge-documents/$sub_scene_id/revisions" | jq -er length)
invalid_body=$(jq -cn --arg sub "$sub_scene_id" \
  '{subSceneId:$sub,contentMd:"# invalid document without KnowledgeIR blocks",revisionNote:"must fail",finalize:false}')
invalid_status=$(printf '%s' "$invalid_body" | curl -sS -o "$problem_file" -w '%{http_code}' \
  -b "$cookie_jar" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
  -H "If-Match: $base_etag" -X PUT --data-binary @- \
  "$web_base_url/api/v1/knowledge-documents/$sub_scene_id")
[ "$invalid_status" = 422 ]
invalid_code=$(jq -er .code "$problem_file")
[ "$invalid_code" = knowledge-markdown-invalid ]
revision_count_after=$(api_request GET "/api/v1/knowledge-documents/$sub_scene_id/revisions" | jq -er length)
[ "$revision_count_after" = "$revision_count_before" ]

verify_stage=alignment-proposal
align_body=$(jq -cn --arg revision "$revision_id" --arg material "$material_id" \
  '{baseRevisionId:$revision,action:"REGULATORY",regulatoryMaterialIds:[$material]}')
align_job=$(api_request POST "/api/v1/knowledge-documents/$sub_scene_id/alignment-jobs" \
  "$align_body" alignment-check | jq -er .jobId)
wait_job_success "$align_job"
proposal_json=$(api_request GET "/api/v1/knowledge-documents/$sub_scene_id/alignment-proposals" | jq -er '.[0]')
proposal_id=$(printf '%s' "$proposal_json" | jq -er .id)
printf '%s' "$proposal_json" | jq -er \
  '.status == "READY" and .structuredPatch.operation == "replaceKnowledgeIr" and (.structuredPatch.diff.addedRuleIds | length) >= 1' >/dev/null

verify_stage=etag-adoption
stale_status=$(curl -sS -o "$problem_file" -w '%{http_code}' -b "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'If-Match: "stale-etag"' -X POST \
  "$web_base_url/api/v1/alignment-proposals/$proposal_id/adopt")
[ "$stale_status" = 412 ]
adopted_json=$(curl -sS -D "$headers_file" -b "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H "If-Match: $base_etag" -X POST "$web_base_url/api/v1/alignment-proposals/$proposal_id/adopt")
adopted_status=$(awk 'toupper($1) ~ /^HTTP\// {status=$2} END {print status}' "$headers_file")
if [ "$adopted_status" != 200 ]; then
  printf '%s\n' "$adopted_json" >&2
  exit 1
fi
adopted_revision=$(printf '%s' "$adopted_json" | jq -er .revisionId)
[ "$adopted_revision" != "$revision_id" ]

verify_stage=database-invariants
migrations=$(psql_query \
  "SELECT string_agg(version || ':' || success, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8','9','10','11');")
assert_equal "$migrations" '1:true,2:true,3:true,4:true,5:true,6:true,7:true,8:true,9:true,10:true,11:true' migrations
run_stage=$(psql_query "SELECT stage FROM extraction_run WHERE job_id='$extract_job';")
assert_equal "$run_stage" SUCCEEDED extraction-stage
map_rows=$(psql_query "SELECT COUNT(*) FROM extraction_map_result em JOIN extraction_run er ON er.id=em.run_id WHERE er.job_id='$extract_job';")
assert_at_least "$map_rows" 1 extraction-map-rows
reduce_rows=$(psql_query "SELECT COUNT(*) FROM extraction_reduce_result rr JOIN extraction_run er ON er.id=rr.run_id WHERE er.job_id='$extract_job';")
assert_equal "$reduce_rows" 1 extraction-reduce-rows
projection_rows=$(psql_query "SELECT COUNT(*) FROM document_revision_projection WHERE revision_id IN ('$revision_id','$adopted_revision');")
assert_equal "$projection_rows" 2 revision-projection-rows
holdout_rows=$(psql_query "SELECT COUNT(*) FROM extraction_run_material erm JOIN extraction_run er ON er.id=erm.run_id WHERE er.job_id='$extract_job' AND erm.partition='LABELED_HOLDOUT';")
assert_equal "$holdout_rows" 0 holdout-material-rows
proposal_status=$(psql_query "SELECT CASE WHEN a.proposal_id IS NULL THEN p.status ELSE 'ADOPTED' END FROM alignment_proposal p LEFT JOIN alignment_proposal_adoption a ON a.proposal_id=p.id WHERE p.id='$proposal_id';")
assert_equal "$proposal_status" ADOPTED alignment-proposal-status

printf 'KNOWLEDGE_WORKFLOW_OK migrations=%s source_refs=%s map_rows=%s reduce_rows=%s projections=%s invalid_markdown=%s stale_adopt=%s proposal=%s\n' \
  "$migrations" "$source_ref_count" "$map_rows" "$reduce_rows" "$projection_rows" "$invalid_status" "$stale_status" "$proposal_status"

cleanup_compose
trap - 0 1 2 15
containers_left=$(docker ps -a --filter label=com.docker.compose.project="$project" -q | wc -l | tr -d ' ')
volumes_left=$(docker volume ls --filter label=com.docker.compose.project="$project" -q | wc -l | tr -d ' ')
printf 'CLEANUP_OK containers=%s volumes=%s\n' "$containers_left" "$volumes_left"
[ "$containers_left" = 0 ]
[ "$volumes_left" = 0 ]
