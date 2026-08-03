# Initial threat model

## Protected assets

- Source documents and extracted knowledge.
- Model credentials, authentication sessions and bootstrap secrets.
- Immutable release manifests and audit history.
- Worker execution capacity and object-store integrity.

## Primary controls

- Same-origin secure session cookies, CSRF protection and server-side RBAC.
- Write-only encrypted model credentials and an explicit model-host allowlist.
- Direct-to-quarantine object uploads with size, MIME, archive, malware and resource-budget checks.
- Optimistic locking for documents and configuration; idempotency for long commands.
- PostgreSQL leases for at-most-one active job attempt and ordered SSE replay.
- Redacted application logs; prompt and document bodies are excluded by default.
- Uploaded Skill code is inert until the sandbox milestone.

## Required negative tests

Test cross-role access, stale ETags, replayed idempotency keys, SSRF through DNS and redirects, forged MIME types, malformed OOXML, Zip Slip, compression bombs, prompt injection, secret leakage, worker crashes, SSE reconnects and partial-release rollback.
