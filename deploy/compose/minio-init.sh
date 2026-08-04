#!/bin/sh
# Version-controlled MinIO bootstrap: creates the buckets used by the workbench.
#
# CORS for local browser uploads is configured on the minio service itself via
# the MINIO_API_CORS_ALLOW_ORIGIN environment variable (exact origins only, never
# wildcard). This MinIO release (RELEASE.2025-04-22T22-12-26Z, mc
# RELEASE.2025-04-16) exposes only cors_allow_origin among CORS keys and does NOT
# implement the S3 PutBucketCors API (`mc cors set` / admin CORS keys fail), so
# no CORS tuning is attempted here beyond bucket creation.
set -e

mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing \
  local/kmp-quarantine \
  local/kmp-verified-knowledge \
  local/kmp-verified-holdout \
  local/kmp-assets
