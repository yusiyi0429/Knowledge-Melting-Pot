# Private single-host deployment

This Compose stack is the pilot deployment baseline. PostgreSQL, ClamAV, API and Worker stay private. Nginx publishes the workbench port; MinIO publishes API/console ports on loopback only because the browser performs local presigned multipart uploads directly.

## Start

```bash
cp .env.example .env
# Replace every change-me value, set a unique bootstrap password, and generate
# KMP_MODEL_MASTER_KEY with `openssl rand -base64 32`.
docker compose --env-file .env -f deploy/compose/compose.yaml up --build
```

Open `http://localhost:8088`. The bootstrap administrator must change the initial password immediately.

Release-bound evaluation is intentionally a separate process. Start the model-backed Evaluation Worker with:

```bash
docker compose --env-file .env -f deploy/compose/compose.yaml \
  --profile evaluation up --build
```

The main Worker never claims `EVALUATE`. For the reviewed declarative `SANDBOX_V1` runtime, enable both profiles and set `KMP_SKILL_SANDBOX_ENABLED=true`:

```bash
docker compose --env-file .env -f deploy/compose/compose.yaml \
  --profile evaluation --profile skill-sandbox up --build
```

The sandbox publishes no host port, has no external network, receives no application secrets, and cannot run arbitrary uploaded scripts. Do not treat this profile as authorization for Shell, Python or binary Skill packages.

## Operations gates

Run the isolated performance and backup/restore gates before a production candidate is accepted:

```bash
scripts/verify-performance.sh
scripts/verify-backup-restore.sh
```

The default performance gate sends 300 CSRF/Session requests at concurrency 16 through Nginx and enforces zero errors plus p95 ≤ 1500 ms. Tune the local acceptance budget with `KMP_PERFORMANCE_*`; keep the chosen budget version-controlled for a release.

`backup-compose.sh` quiesces running Web/API/Worker processes, backs up PostgreSQL and all four MinIO buckets, creates `SHA256SUMS`, and restarts only the services it stopped. `restore-compose.sh` refuses an existing project and requires `KMP_RESTORE_CONFIRM=RESTORE_EMPTY_PROJECT`. See [Backup and restore](../../docs/operations/backup-restore.md).

## Production requirements

- The model master key is mounted as a Docker secret; move database, MinIO, and bootstrap credentials
  to the deployment platform's secret provider before using non-local data.
- Pin and mirror every image by digest after vulnerability review.
- Keep the multi-architecture ClamAV Debian image on a reviewed 1.4.x patch release; the Alpine `clamav/clamav:1.4` tag does not provide an ARM64 manifest.
- Terminate TLS before Nginx and keep secure session cookies enabled.
- Keep API/Worker/Web as the non-root users built into their images. Compose drops all Linux capabilities, applies `no-new-privileges`, PID/resource limits and bounded `tmpfs`; Web and the Skill sandbox additionally use read-only root filesystems.
- Back up PostgreSQL and object storage together; a release is recoverable only when both are restored to a consistent point.
- Restrict `KMP_ALLOWED_MODEL_HOSTS` to reviewed model gateways.

Real Provider connectivity is opt-in because CI does not store model credentials. With the
workbench running, set `KMP_PROVIDER_E2E_PASSWORD` and at least one of
`KMP_PROVIDER_E2E_OPENAI_API_KEY` / `KMP_PROVIDER_E2E_DASHSCOPE_API_KEY`, then run
`scripts/verify-model-providers.sh`. The script uses read-only authenticated probes, does not
print credentials, and soft-deletes its temporary connections.
- Configure object-store lifecycle policies for quarantine and failed uploads.
- Do not expose PostgreSQL, ClamAV, API or Worker ports to the host network. In the local-only browser-upload deployment, MinIO may be published only on `127.0.0.1`; use a reviewed TLS endpoint and corresponding CSP/CORS allowlist for any non-local deployment.

The API and Worker are separate images so Worker concurrency can be changed without increasing HTTP replicas. Kubernetes manifests are intentionally outside the first deployment scope.

The baseline keeps `KMP_AGENT_ENABLED=false`; enable the Agent Runtime only after a reviewed model connection and Worker-specific credentials are configured. See [Compose runtime verification](../../docs/operations/runtime-verification.md) for migration, readiness, secret-mount, SBOM, and dependency-convergence checks.
