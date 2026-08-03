#!/bin/sh
set -eu

cd "$(dirname "$0")/../docs/product/originals"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum --check SHA256SUMS
else
  while IFS='  ' read -r expected file; do
    [ -n "$expected" ] || continue
    actual=$(shasum -a 256 "$file" | awk '{print $1}')
    [ "$actual" = "$expected" ] || {
      echo "Checksum mismatch: $file" >&2
      exit 1
    }
    echo "$file: OK"
  done < SHA256SUMS
fi
