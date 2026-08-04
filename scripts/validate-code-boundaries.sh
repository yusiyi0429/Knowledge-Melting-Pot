#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
expected="$repo_root/backend/workbench-agent-adapter/pom.xml"

matches=$(find "$repo_root/backend" -type f -name pom.xml \
  -exec grep -l '<artifactId>agent-core-java</artifactId>' {} + || true)
[ "$matches" = "$expected" ] || {
  echo "agent-core-java must be declared only by workbench-agent-adapter." >&2
  echo "Found:" >&2
  printf '%s\n' "$matches" >&2
  exit 1
}

if find "$repo_root/backend" -type f -name '*.java' \
  ! -path '*/workbench-agent-adapter/*' \
  -exec grep -nH -E '^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?com\.openjiuwen\.' {} + \
  | grep -q .; then
  echo "agent-core-java imports escaped the adapter module." >&2
  exit 1
fi

if find "$repo_root/backend" -type f -name '*.java' \
  ! -path '*/workbench-object-storage-adapter/*' \
  -exec grep -nH -E '^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?software\.amazon\.awssdk\.' {} + \
  | grep -q .; then
  echo "AWS SDK v2 imports escaped the object-storage adapter module." >&2
  exit 1
fi

if find "$repo_root/backend" -type f -name '*.java' \
  ! -path '*/workbench-content-adapter/*' \
  -exec grep -nH -E '^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?(org\.apache\.tika\.|org\.apache\.pdfbox\.)' {} + \
  | grep -q .; then
  echo "Tika/PDFBox imports escaped the content adapter module." >&2
  exit 1
fi

# POI is additionally used by the Worker asset renderer: RULE_CATALOG assets
# generate XLSX files (cells are escaped against formula injection). Parsing of
# untrusted OOXML input stays exclusively inside the content adapter.
if find "$repo_root/backend" -type f -name '*.java' \
  ! -path '*/workbench-content-adapter/*' \
  ! -path '*/workbench-worker/src/main/java/com/knowledgemeltingpot/workbench/worker/asset/AssetContentFactory.java' \
  -exec grep -nH -E '^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?org\.apache\.poi\.' {} + \
  | grep -q .; then
  echo "POI imports escaped the content adapter and the worker asset renderer." >&2
  exit 1
fi

echo "Agent runtime boundary: OK"
echo "Object-storage boundary: OK"
echo "Content adapter boundary: OK"
