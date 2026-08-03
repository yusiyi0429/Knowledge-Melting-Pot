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

if rg -n '^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?software\.amazon\.awssdk\.' "$repo_root/backend" \
  --glob '*.java' \
  --glob '!**/workbench-object-storage-adapter/**' >/dev/null; then
  echo "AWS SDK v2 imports escaped the object-storage adapter module." >&2
  exit 1
fi

if rg -n '^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?(org\.apache\.tika\.|org\.apache\.pdfbox\.|org\.apache\.poi\.)' \
  "$repo_root/backend" \
  --glob '*.java' \
  --glob '!**/workbench-content-adapter/**' >/dev/null; then
  echo "Tika/PDFBox/POI imports escaped the content adapter module." >&2
  exit 1
fi

echo "Agent runtime boundary: OK"
echo "Object-storage boundary: OK"
echo "Content adapter boundary: OK"
