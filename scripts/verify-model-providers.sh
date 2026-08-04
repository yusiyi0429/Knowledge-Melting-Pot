#!/bin/sh
set -eu

# Opt-in acceptance against real Provider accounts. This script never prints
# credentials and cleans up the model connections it creates. It expects a
# running workbench whose model host whitelist includes the selected endpoints.

base_url=${KMP_PROVIDER_E2E_BASE_URL:-http://localhost:8088}
username=${KMP_PROVIDER_E2E_USERNAME:-admin}
password=${KMP_PROVIDER_E2E_PASSWORD:-}
openai_key=${KMP_PROVIDER_E2E_OPENAI_API_KEY:-}
dashscope_key=${KMP_PROVIDER_E2E_DASHSCOPE_API_KEY:-}
openai_url=${KMP_PROVIDER_E2E_OPENAI_BASE_URL:-https://api.openai.com/v1}
dashscope_url=${KMP_PROVIDER_E2E_DASHSCOPE_BASE_URL:-https://dashscope.aliyuncs.com/api/v1}

if [ -z "$password" ]; then
  echo "KMP_PROVIDER_E2E_PASSWORD is required" >&2
  exit 2
fi
if [ -z "$openai_key" ] && [ -z "$dashscope_key" ]; then
  echo "Set at least one Provider E2E API key" >&2
  exit 2
fi

runtime_tmp=$(mktemp -d "${TMPDIR:-/tmp}/kmp-provider-e2e.XXXXXX")
cookie_jar="$runtime_tmp/cookies.txt"
created_ids=""

cleanup() {
  result=$?
  trap - 0 1 2 15
  for connection_id in $created_ids; do
    curl -sS -o /dev/null -b "$cookie_jar" -H "$csrf_header: $csrf_token" \
      -X DELETE "$base_url/api/v1/model-connections/$connection_id" || true
  done
  : >"$cookie_jar" 2>/dev/null || true
  rm -f "$cookie_jar"
  rmdir "$runtime_tmp" 2>/dev/null || true
  exit "$result"
}
trap cleanup 0
trap 'exit 130' 1 2 15

csrf_json=$(curl -fsS -c "$cookie_jar" "$base_url/api/v1/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)
login_body=$(printf '%s' "$password" | jq -Rsc --arg username "$username" \
  '{username:$username,password:.}')
login_status=$(printf '%s' "$login_body" | curl -sS -o /dev/null -w '%{http_code}' \
  -b "$cookie_jar" -c "$cookie_jar" -H "$csrf_header: $csrf_token" \
  -H 'Content-Type: application/json' --data-binary @- "$base_url/api/v1/auth/login")
login_body=
if [ "$login_status" != 204 ]; then
  echo "Provider E2E login failed with HTTP $login_status" >&2
  exit 1
fi

verify_provider() {
  provider=$1
  provider_url=$2
  provider_key=$3
  label=$4
  unique_name="provider-e2e-$label-$(date +%s)-$$"
  create_body=$(printf '%s' "$provider_key" | jq -Rsc \
    --arg name "$unique_name" --arg provider "$provider" --arg baseUrl "$provider_url" \
    '{name:$name,provider:$provider,baseUrl:$baseUrl,credential:.,enabled:true}')
  create_response=$(printf '%s' "$create_body" | curl -fsS -b "$cookie_jar" \
    -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
    --data-binary @- "$base_url/api/v1/model-connections")
  create_body=
  connection_id=$(printf '%s' "$create_response" | jq -er .id)
  created_ids="$created_ids $connection_id"

  test_result=$(printf '{}' | curl -fsS -b "$cookie_jar" \
    -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
    --data-binary @- "$base_url/api/v1/model-connections/$connection_id/connection-tests")
  if ! printf '%s' "$test_result" | jq -e \
      '.status == "CONNECTED" and .networkAttempted == true and .connectivityVerified == true' \
      >/dev/null; then
    printf '%s' "$test_result" | jq '{status,networkAttempted,connectivityVerified,messageCode,testedAt}' >&2
    echo "PROVIDER_E2E_FAILED provider=$label" >&2
    return 1
  fi
  message_code=$(printf '%s' "$test_result" | jq -er .messageCode)
  echo "PROVIDER_E2E_OK provider=$label messageCode=$message_code"
}

if [ -n "$openai_key" ]; then
  verify_provider OPENAI_COMPATIBLE "$openai_url" "$openai_key" openai-compatible
fi
if [ -n "$dashscope_key" ]; then
  verify_provider DASHSCOPE "$dashscope_url" "$dashscope_key" dashscope-native
fi
