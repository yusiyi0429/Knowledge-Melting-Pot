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
holdout_file="$runtime_tmp/labeled-holdout.txt"
problem_file="$runtime_tmp/problem.json"

compose() {
  docker compose --profile evaluation --profile skill-sandbox \
    -p "$project" -f "$compose_file" -f "$override_file" "$@"
}

cleanup_compose() {
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

verify_stage=initialization
finish_runtime() {
  result=$?
  trap - 0 1 2 15
  if [ "$result" -ne 0 ]; then
    echo "KNOWLEDGE_WORKFLOW_FAILED stage=$verify_stage" >&2
    api_failure_id=$(compose ps -q api 2>/dev/null || true)
    if [ -n "$api_failure_id" ]; then
      docker logs --tail 400 "$api_failure_id" 2>&1 | tail -400 >&2 || true
    fi
    for worker_service in worker evaluation-worker; do
      worker_failure_id=$(compose ps -q "$worker_service" 2>/dev/null || true)
      if [ -n "$worker_failure_id" ]; then
        docker logs --tail 400 "$worker_failure_id" 2>&1 | tail -400 >&2 || true
      fi
    done
    postgres_failure_id=$(compose ps -q postgres 2>/dev/null || true)
    if [ -n "$postgres_failure_id" ]; then
      docker logs --tail 100 "$postgres_failure_id" 2>&1 | tail -100 >&2 || true
    fi
  fi
  cleanup_compose
  for file in "$cookie_jar" "$headers_file" "$sample_file" "$holdout_file" "$problem_file"; do
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
compose config --quiet
if [ "${KMP_KNOWLEDGE_BUILD:-true}" = true ]; then
  compose build api worker evaluation-worker skill-sandbox web
fi
compose up -d --no-build api worker evaluation-worker skill-sandbox web >/dev/null

api_id=$(compose ps -q api)
postgres_id=$(compose ps -q postgres)
sandbox_id=$(compose ps -q skill-sandbox)

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

verify_stage=skill-sandbox-negative-controls
sandbox_health=$(docker exec "$sandbox_id" python -I -c \
  "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8081/health', timeout=2).read().decode())")
printf '%s' "$sandbox_health" | jq -er '.status == "UP"' >/dev/null
sandbox_rejection=$(docker exec "$sandbox_id" python -I -c \
  "import http.client,json; body=json.dumps({'version':1,'invocationId':'negative','program':{'kind':'SHELL','rules':[],'defaultPrediction':'x'},'input':'x','script':'print(1)'}); c=http.client.HTTPConnection('127.0.0.1',8081,timeout=2); c.request('POST','/v1/execute',body,{'Content-Type':'application/json'}); r=c.getresponse(); print(r.status,r.read().decode())")
printf '%s' "$sandbox_rejection" | jq -Rer 'startswith("422 ") and contains("SANDBOX_SCHEMA_INVALID")' >/dev/null
if docker exec "$sandbox_id" python -I -c \
  "import urllib.request; urllib.request.urlopen('http://example.com',timeout=2).read(1)" >/dev/null 2>&1; then
  echo 'INVARIANT_FAILED sandbox-egress-open' >&2
  exit 1
fi
if docker exec "$sandbox_id" sh -c 'printf blocked >/opt/kmp/write-probe' >/dev/null 2>&1; then
  echo 'INVARIANT_FAILED sandbox-read-only' >&2
  exit 1
fi
sandbox_hardening=$(docker inspect "$sandbox_id" | jq -er \
  '.[0] | (.HostConfig.ReadonlyRootfs == true) and (.HostConfig.CapDrop == ["ALL"]) and
   (.HostConfig.SecurityOpt | any(. == "no-new-privileges:true")) and
   ([.Config.Env[] | select(test("KMP_|SPRING_|PASSWORD|SECRET|TOKEN|API_KEY"))] | length == 0)')
[ "$sandbox_hardening" = true ]

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
skill_manifest='{"executionMode":"SANDBOX_V1","schemaVersion":"kmp-sandbox/v1","name":"loan-risk-classifier","program":{"kind":"CLASSIFY_CONTAINS","rules":[{"containsAny":["逾期120","严重减值","重组失败"],"prediction":"次级"},{"containsAny":["逾期30","还款能力下降"],"prediction":"关注"}],"defaultPrediction":"正常"}}'
skill_hash=$(printf '%s' "$skill_manifest" | shasum -a 256 | awk '{print $1}')
skill_body=$(jq -cn --arg manifest "$skill_manifest" --arg hash "$skill_hash" \
  '{name:"local-acceptance-skill",description:"resource-only deterministic fixture",manifest:$manifest,packageHash:$hash}')
skill_id=$(api_request POST /api/v1/skills "$skill_body" skill-create-check | jq -er .id)
skill_version_id=$(api_request GET "/api/v1/skills/$skill_id" | jq -er '.versions[0].id')

verify_stage=agent-mount-version-setup
scene_scope=$(api_request GET "/api/v1/agent-mounts?scope=SCENE&scopeId=$scene_id")
scene_etag=$(printf '%s' "$scene_scope" | jq -er .etag)
extract_mount_body=$(jq -cn --arg scene "$scene_id" --arg model "$model_version_id" --arg skill "$skill_version_id" \
  '{scope:"SCENE",scopeId:$scene,role:"KNOWLEDGE_EXTRACTOR",enabled:true,modelConfigVersionId:$model,skillVersionId:$skill,options:{strategy:"deterministic-acceptance"}}')
extract_mount_response=$(printf '%s' "$extract_mount_body" | curl -sS -D "$headers_file" -b "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' -H "If-Match: $scene_etag" \
  -X POST --data-binary @- "$web_base_url/api/v1/agent-mounts/versions")
extract_mount_status=$(awk 'toupper($1) ~ /^HTTP\// {status=$2} END {print status}' "$headers_file")
if [ "$extract_mount_status" != 200 ]; then
  printf '%s\n' "$extract_mount_response" >&2
  exit 1
fi
scene_etag=$(printf '%s' "$extract_mount_response" | jq -er .etag)
align_import_body=$(jq -cn --arg scene "$scene_id" --arg model "$model_version_id" --arg skill "$skill_version_id" \
  '{scope:"SCENE",scopeId:$scene,roles:[
    {role:"ALIGNMENT_REVIEWER",enabled:true,modelConfigVersionId:$model,skillVersionId:$skill,options:{strategy:"deterministic-acceptance"}},
    {role:"RULE_CATALOG_GENERATOR",enabled:true,modelConfigVersionId:$model,skillVersionId:$skill,options:{output:"xlsx-json"}},
    {role:"DECISION_FLOW_GENERATOR",enabled:true,modelConfigVersionId:$model,skillVersionId:$skill,options:{output:"markdown-json-mermaid"}},
    {role:"SKILL_PACKAGER",enabled:true,modelConfigVersionId:$model,skillVersionId:$skill,options:{executionMode:"RESOURCE_ONLY"}},
    {role:"QA_EVALUATOR",enabled:true,modelConfigVersionId:$model,skillVersionId:$skill,options:{scoring:"exact-match",expectedVisibility:"worker-only"}}
  ]}')
align_preview=$(api_request POST /api/v1/configuration-imports/previews "$align_import_body")
align_import_id=$(printf '%s' "$align_preview" | jq -er .id)
align_manifest_hash=$(printf '%s' "$align_preview" | jq -er .manifestHash)
align_apply=$(curl -fsS -b "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H "If-Match: $align_manifest_hash" -X POST \
  "$web_base_url/api/v1/configuration-imports/$align_import_id/apply")
printf '%s' "$align_apply" | jq -er '.mounts | length == 6' >/dev/null
effective_agents=$(api_request GET "/api/v1/agent-mounts/effective?sceneId=$scene_id&subSceneId=$sub_scene_id")
printf '%s' "$effective_agents" | jq -er \
  '[.[] | select((.role == "KNOWLEDGE_EXTRACTOR" or .role == "ALIGNMENT_REVIEWER" or
    .role == "RULE_CATALOG_GENERATOR" or .role == "DECISION_FLOW_GENERATOR" or
    .role == "SKILL_PACKAGER" or .role == "QA_EVALUATOR") and .enabled == true and
    .modelConfigVersionId != null and .skillVersionId != null)] | length == 6' >/dev/null

verify_stage=global-scene-explorer-setup
global_scope=$(api_request GET "/api/v1/agent-mounts?scope=GLOBAL")
global_etag=$(printf '%s' "$global_scope" | jq -er .etag)
explorer_mount_body=$(jq -cn --arg model "$model_version_id" --arg skill "$skill_version_id" \
  '{scope:"GLOBAL",role:"SCENE_EXPLORER",enabled:true,modelConfigVersionId:$model,skillVersionId:$skill,options:{strategy:"deterministic-acceptance"}}')
explorer_mount_response=$(printf '%s' "$explorer_mount_body" | curl -sS -D "$headers_file" -b "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' -H "If-Match: $global_etag" \
  -X POST --data-binary @- "$web_base_url/api/v1/agent-mounts/versions")
explorer_mount_status=$(awk 'toupper($1) ~ /^HTTP\// {status=$2} END {print status}' "$headers_file")
if [ "$explorer_mount_status" != 200 ]; then
  printf '%s\n' "$explorer_mount_response" >&2
  exit 1
fi

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

verify_stage=holdout-upload
printf '%s\n' \
  '{"caseId":"late-120","input":"客户贷款逾期120天，应如何分类？","expected":"次级","tags":["逾期"]}' \
  '{"caseId":"late-30","input":"客户贷款逾期30天且还款能力下降，应如何分类？","expected":"关注","tags":["观察"]}' \
  '{"caseId":"performing","input":"客户正常履约且经营稳定，应如何分类？","expected":"正常","tags":["正常"]}' >"$holdout_file"
holdout_size=$(wc -c <"$holdout_file" | tr -d ' ')
holdout_sha=$(shasum -a 256 "$holdout_file" | awk '{print $1}')
holdout_intent_body=$(jq -cn --arg name labeled-holdout.txt --arg sha "$holdout_sha" \
  --arg round "$round_id" --arg sub "$sub_scene_id" --argjson size "$holdout_size" \
  '{fileName:$name,sizeBytes:$size,mediaType:"text/plain",sha256:$sha,roundId:$round,subSceneIds:[$sub],partition:"LABELED_HOLDOUT",shareScope:"ROUND",regulatorySource:false}')
holdout_intent=$(api_request POST /api/v1/materials/upload-intents "$holdout_intent_body" holdout-intent-check)
holdout_intent_id=$(printf '%s' "$holdout_intent" | jq -er .id)
holdout_material_id=$(printf '%s' "$holdout_intent" | jq -er .materialId)
holdout_part_url=$(printf '%s' "$holdout_intent" | jq -er '.parts[0].url')
holdout_etag=$(curl -fsS -D - -o /dev/null -X PUT -T "$holdout_file" "$holdout_part_url" \
  | tr -d '\r' | sed -n 's/^[Ee][Tt][Aa][Gg]: *//p' | tr -d '"' | head -1)
[ -n "$holdout_etag" ]
holdout_complete=$(jq -cn --arg etag "$holdout_etag" '{parts:[{partNumber:1,etag:$etag}]}')
holdout_ingest_job=$(api_request POST "/api/v1/materials/upload-intents/$holdout_intent_id/complete" \
  "$holdout_complete" | jq -er .jobId)
wait_job_success "$holdout_ingest_job"

verify_stage=map-reduce-extraction
extract_body=$(jq -cn --arg round "$round_id" '{roundId:$round}')
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

verify_stage=document-finalization
adopted_etag=$(printf '%s' "$adopted_json" | jq -er .etag)
finalize_body=$(printf '%s' "$adopted_json" | jq -c \
  '{subSceneId:.subSceneId,contentMd:.contentMd,revisionNote:"V14 evaluation release candidate",finalize:true}')
finalized_json=$(printf '%s' "$finalize_body" | curl -fsS -b "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' -H "If-Match: $adopted_etag" \
  -X PUT --data-binary @- "$web_base_url/api/v1/knowledge-documents/$sub_scene_id")
finalized_revision=$(printf '%s' "$finalized_json" | jq -er 'select(.finalized == true) | .revisionId')

verify_stage=five-asset-generation
asset_body=$(jq -cn --arg revision "$finalized_revision" \
  '{documentRevisionId:$revision,types:["RULE_CATALOG","DECISION_FLOW","SKILL_PACKAGE","QA_PAIRS","EVALUATION_SET"]}')
asset_job=$(api_request POST "/api/v1/subscenes/$sub_scene_id/asset-generation-jobs" \
  "$asset_body" all-assets-check | jq -er .jobId)
wait_job_success "$asset_job"
asset_list=$(api_request GET "/api/v1/subscenes/$sub_scene_id/assets")
printf '%s' "$asset_list" | jq -er --arg revision "$finalized_revision" \
  'length == 5 and all(.[]; .status == "READY" and .documentRevisionId == $revision)' >/dev/null
asset_executions=$(api_request GET "/api/v1/jobs/$asset_job/agent-executions")
printf '%s' "$asset_executions" | jq -er \
  'length == 5 and all(.[]; .status == "SUCCEEDED" and (.effectiveConfigHash | length) == 64 and
    (.inputHash | length) == 64 and (.outputHash | length) == 64 and .assetId != null) and
    ([.[].role] | unique | sort) == ["DECISION_FLOW_GENERATOR","QA_EVALUATOR","RULE_CATALOG_GENERATOR","SKILL_PACKAGER"]' >/dev/null

verify_stage=release-publication
release_body=$(jq -cn --arg sub "$sub_scene_id" \
  '{tag:"v14.0.0",selectedSubSceneIds:[$sub],note:"V14 本地确定性评测验收",confirmed:true,expectedBaseReleaseId:null}')
release_validation=$(api_request POST "/api/v1/scenes/$scene_id/release-validations" "$release_body")
printf '%s' "$release_validation" | jq -er '.ready == true and .coverage == "FULL" and (.blockers | length) == 0' >/dev/null
release_json=$(api_request POST "/api/v1/scenes/$scene_id/releases" "$release_body")
release_id=$(printf '%s' "$release_json" | jq -er .id)
release_hash=$(printf '%s' "$release_json" | jq -er '.manifestSha256 | select(length == 64)')
release_manifest=$(api_request GET "/api/v1/releases/$release_id/manifest")
printf '%s' "$release_manifest" | jq -er \
  --arg sub "$sub_scene_id" --arg model "$model_version_id" --arg skill "$skill_version_id" \
  'any(.subScenes[]; .subSceneId == $sub and any(.agentConfigurations[];
    .role == "QA_EVALUATOR" and .enabled == true and .model.configVersionId == $model and .skill.skillVersionId == $skill)) and
   all(.subScenes[] | select(.subSceneId == $sub) | .assets[];
    .modelConfigVersionId == $model and .skillVersionId == $skill and
    (.effectiveConfigHash | length) == 64 and (.inputHash | length) == 64 and (.outputHash | length) == 64)' >/dev/null

verify_stage=release-bound-holdout-evaluation
evaluation_body=$(jq -cn --arg round "$round_id" '{roundId:$round}')
evaluation_accepted=$(api_request POST "/api/v1/releases/$release_id/subscenes/$sub_scene_id/evaluation-jobs" \
  "$evaluation_body" evaluation-run-check)
evaluation_run_id=$(printf '%s' "$evaluation_accepted" | jq -er .evaluationRunId)
evaluation_job=$(printf '%s' "$evaluation_accepted" | jq -er .jobId)
wait_job_success "$evaluation_job"
evaluation_detail=$(api_request GET "/api/v1/evaluation-runs/$evaluation_run_id")
printf '%s' "$evaluation_detail" | jq -er --arg release "$release_id" \
  '.run.releaseId == $release and .run.status == "SUCCEEDED" and .run.totalCases == 3 and
   .run.passedCases == 3 and .run.failedCases == 0 and .run.errorCases == 0 and
   .run.accuracy == 1 and (.run.caseSetHash | length) == 64 and
   (.cases | length) == 3 and all(.cases[]; .outcome == "PASSED" and .prediction == .expected)' >/dev/null

verify_stage=exploration-staging-upload
exploration_id=$(api_request POST /api/v1/explorations \
  '{"title":"本地授信风险场景探索"}' | jq -er .id)
explore_intent_body=$(jq -cn --arg name regulatory-sample.txt --arg sha "$sha256" \
  --arg session "$exploration_id" --argjson size "$size_bytes" \
  '{fileName:$name,sizeBytes:$size,mediaType:"text/plain",sha256:$sha,explorationSessionId:$session,subSceneIds:[],partition:"SOURCE",shareScope:"ROUND",regulatorySource:false}')
explore_intent_json=$(api_request POST /api/v1/materials/upload-intents "$explore_intent_body" exploration-intent-check)
explore_intent_id=$(printf '%s' "$explore_intent_json" | jq -er .id)
explore_material_id=$(printf '%s' "$explore_intent_json" | jq -er .materialId)
explore_part_url=$(printf '%s' "$explore_intent_json" | jq -er '.parts[0].url')
explore_part_etag=$(curl -fsS -D - -o /dev/null -X PUT -T "$sample_file" "$explore_part_url" \
  | tr -d '\r' | sed -n 's/^[Ee][Tt][Aa][Gg]: *//p' | tr -d '"' | head -1)
[ -n "$explore_part_etag" ]
explore_complete_body=$(jq -cn --arg etag "$explore_part_etag" '{parts:[{partNumber:1,etag:$etag}]}')
explore_ingest_job=$(api_request POST "/api/v1/materials/upload-intents/$explore_intent_id/complete" \
  "$explore_complete_body" | jq -er .jobId)
wait_job_success "$explore_ingest_job"

verify_stage=scene-exploration
explore_job=$(api_request POST "/api/v1/explorations/$exploration_id/analysis-jobs" '{}' \
  exploration-analysis-check | jq -er .jobId)
wait_job_success "$explore_job"
exploration_detail=$(api_request GET "/api/v1/explorations/$exploration_id")
exploration_etag=$(printf '%s' "$exploration_detail" | jq -er .etag)
candidate_id=$(printf '%s' "$exploration_detail" | jq -er '.candidates[0].id')
printf '%s' "$exploration_detail" | jq -er \
  --arg material "$explore_material_id" '.session.status == "READY" and (.candidates | length) == 1 and .candidates[0].materialIds == [$material]' >/dev/null

verify_stage=exploration-acceptance
stale_exploration_status=$(printf '{}' | curl -sS -o "$problem_file" -w '%{http_code}' -b "$cookie_jar" \
  -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' -H 'If-Match: "stale-etag"' \
  -X POST --data-binary @- "$web_base_url/api/v1/explorations/$exploration_id/candidates/$candidate_id/accept")
[ "$stale_exploration_status" = 412 ]
acceptance_json=$(printf '{}' | curl -fsS -b "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H 'Content-Type: application/json' -H "If-Match: $exploration_etag" -X POST --data-binary @- \
  "$web_base_url/api/v1/explorations/$exploration_id/candidates/$candidate_id/accept")
accepted_scene_id=$(printf '%s' "$acceptance_json" | jq -er .sceneId)
accepted_sub_scene_id=$(printf '%s' "$acceptance_json" | jq -er .subSceneId)
accepted_round_id=$(printf '%s' "$acceptance_json" | jq -er .roundId)
printf '%s' "$acceptance_json" | jq -er --arg material "$explore_material_id" '.reusedMaterialIds == [$material]' >/dev/null
formal_materials=$(api_request GET "/api/v1/materials?roundId=$accepted_round_id&subSceneId=$accepted_sub_scene_id")
printf '%s' "$formal_materials" | jq -er --arg material "$explore_material_id" \
  'length == 1 and .[0].id == $material and .[0].binding.partition == "SOURCE"' >/dev/null

verify_stage=search-and-notification
search_json=$(curl -fsS -G -b "$cookie_jar" --data-urlencode 'q=授信' --data-urlencode 'limit=20' \
  "$web_base_url/api/v1/search")
printf '%s' "$search_json" | jq -er --arg scene "$accepted_scene_id" \
  'any(.[]; .sceneId == $scene and .type == "SCENE")' >/dev/null
notification_json=$(api_request GET /api/v1/notifications)
printf '%s' "$notification_json" | jq -er --arg job "$explore_job" \
  '.unreadCount >= 1 and any(.items[]; .resourceId == $job and .type == "JOB_SUCCEEDED")' >/dev/null

verify_stage=database-invariants
migrations=$(psql_query \
  "SELECT string_agg(version || ':' || success, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20');")
assert_equal "$migrations" '1:true,2:true,3:true,4:true,5:true,6:true,7:true,8:true,9:true,10:true,11:true,12:true,13:true,14:true,15:true,16:true,17:true,18:true,19:true,20:true' migrations
run_stage=$(psql_query "SELECT stage FROM extraction_run WHERE job_id='$extract_job';")
assert_equal "$run_stage" SUCCEEDED extraction-stage
role_config_pinned=$(psql_query "SELECT (role_config_version_id IS NOT NULL) || ':' || (role_config_hash IS NOT NULL) FROM extraction_run WHERE job_id='$extract_job';")
assert_equal "$role_config_pinned" true:true extraction-role-config
map_rows=$(psql_query "SELECT COUNT(*) FROM extraction_map_result em JOIN extraction_run er ON er.id=em.run_id WHERE er.job_id='$extract_job';")
assert_at_least "$map_rows" 1 extraction-map-rows
reduce_rows=$(psql_query "SELECT COUNT(*) FROM extraction_reduce_result rr JOIN extraction_run er ON er.id=rr.run_id WHERE er.job_id='$extract_job';")
assert_equal "$reduce_rows" 1 extraction-reduce-rows
projection_rows=$(psql_query "SELECT COUNT(*) FROM document_revision_projection WHERE revision_id IN ('$revision_id','$adopted_revision');")
assert_equal "$projection_rows" 2 revision-projection-rows
holdout_rows=$(psql_query "SELECT COUNT(*) FROM extraction_run_material erm JOIN extraction_run er ON er.id=erm.run_id WHERE er.job_id='$extract_job' AND erm.partition='LABELED_HOLDOUT';")
assert_equal "$holdout_rows" 0 holdout-material-rows
holdout_binding=$(psql_query "SELECT COUNT(*) FROM round_material WHERE material_id='$holdout_material_id' AND round_id='$round_id' AND sub_scene_id='$sub_scene_id' AND partition='LABELED_HOLDOUT' AND active=true;")
assert_equal "$holdout_binding" 1 holdout-binding
evaluation_worker_claim=$(psql_query "SELECT COUNT(*) FROM job_event WHERE job_id='$evaluation_job' AND event_type='started' AND payload->>'worker' LIKE 'evaluation-worker-%';")
assert_equal "$evaluation_worker_claim" 1 evaluation-worker-claim
evaluation_state=$(psql_query "SELECT status || ':' || total_cases || ':' || passed_cases || ':' || accuracy FROM evaluation_run WHERE id='$evaluation_run_id';")
assert_equal "$evaluation_state" 'SUCCEEDED:3:3:1.000000' evaluation-state
evaluation_cases=$(psql_query "SELECT COUNT(*) FROM evaluation_case WHERE evaluation_run_id='$evaluation_run_id';")
assert_equal "$evaluation_cases" 3 evaluation-cases
evaluation_results=$(psql_query "SELECT COUNT(*) FROM evaluation_case_result WHERE evaluation_run_id='$evaluation_run_id' AND outcome='PASSED';")
assert_equal "$evaluation_results" 3 evaluation-results
asset_agent_attempts=$(psql_query "SELECT COUNT(*) || ':' || COUNT(DISTINCT asset_id) || ':' || COUNT(DISTINCT asset_type) FROM agent_execution_attempt WHERE job_id='$asset_job' AND status='SUCCEEDED' AND length(output_hash)=64;")
assert_equal "$asset_agent_attempts" '5:5:5' asset-agent-attempts
proposal_status=$(psql_query "SELECT CASE WHEN a.proposal_id IS NULL THEN p.status ELSE 'ADOPTED' END FROM alignment_proposal p LEFT JOIN alignment_proposal_adoption a ON a.proposal_id=p.id WHERE p.id='$proposal_id';")
assert_equal "$proposal_status" ADOPTED alignment-proposal-status
alignment_role_config=$(psql_query "SELECT (payload ? 'roleConfigVersionId') || ':' || (payload ? 'roleConfigHash') FROM job WHERE id='$align_job';")
assert_equal "$alignment_role_config" true:true alignment-role-config
import_applied=$(psql_query "SELECT COUNT(*) FROM configuration_import_application WHERE import_id='$align_import_id';")
assert_equal "$import_applied" 1 configuration-import-application
exploration_state=$(psql_query "SELECT status FROM exploration_session WHERE id='$exploration_id';")
assert_equal "$exploration_state" ACCEPTED exploration-state
reused_blob=$(psql_query "SELECT (SELECT blob_id FROM material WHERE id='$material_id') = (SELECT blob_id FROM material WHERE id='$explore_material_id');")
assert_equal "$reused_blob" t exploration-blob-reused
accepted_binding=$(psql_query "SELECT COUNT(*) FROM round_material WHERE material_id='$explore_material_id' AND round_id='$accepted_round_id' AND sub_scene_id='$accepted_sub_scene_id' AND partition='SOURCE';")
assert_equal "$accepted_binding" 1 exploration-material-binding
candidate_material=$(psql_query "SELECT COUNT(*) FROM exploration_candidate_material WHERE candidate_id='$candidate_id' AND material_id='$explore_material_id';")
assert_equal "$candidate_material" 1 exploration-candidate-material
explore_notification=$(psql_query "SELECT COUNT(*) FROM user_notification WHERE resource_id='$explore_job' AND notification_type='JOB_SUCCEEDED';")
assert_equal "$explore_notification" 1 exploration-notification

printf 'KNOWLEDGE_WORKFLOW_OK migrations=%s source_refs=%s map_rows=%s reduce_rows=%s projections=%s role_config=%s asset_agents=%s import_applied=%s invalid_markdown=%s stale_adopt=%s proposal=%s release=%s evaluation=%s:%s/%s accuracy=1.000000 exploration=%s blob_reused=%s search=true notification=%s\n' \
  "$migrations" "$source_ref_count" "$map_rows" "$reduce_rows" "$projection_rows" "$role_config_pinned" "$asset_agent_attempts" "$import_applied" "$invalid_status" "$stale_status" "$proposal_status" "$release_hash" "$evaluation_state" "$evaluation_results" "$evaluation_cases" "$exploration_state" "$reused_blob" "$explore_notification"

cleanup_compose
trap - 0 1 2 15
containers_left=$(docker ps -a --filter label=com.docker.compose.project="$project" -q | wc -l | tr -d ' ')
volumes_left=$(docker volume ls --filter label=com.docker.compose.project="$project" -q | wc -l | tr -d ' ')
printf 'CLEANUP_OK containers=%s volumes=%s\n' "$containers_left" "$volumes_left"
[ "$containers_left" = 0 ]
[ "$volumes_left" = 0 ]
