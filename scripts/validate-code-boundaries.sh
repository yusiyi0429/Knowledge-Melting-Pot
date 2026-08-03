#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
expected="$repo_root/backend/workbench-agent-adapter/pom.xml"

matches=$(rg -l '<artifactId>agent-core-java</artifactId>' "$repo_root/backend" --glob pom.xml || true)
[ "$matches" = "$expected" ] || {
  echo "agent-core-java must be declared only by workbench-agent-adapter." >&2
  echo "Found:" >&2
  printf '%s\n' "$matches" >&2
  exit 1
}

if rg -n '^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?com\.openjiuwen\.' "$repo_root/backend" \
  --glob '*.java' \
  --glob '!**/workbench-agent-adapter/**' >/dev/null; then
  echo "agent-core-java imports escaped the adapter module." >&2
  exit 1
fi

echo "Agent runtime boundary: OK"
