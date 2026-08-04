# Private single-host deployment

This Compose stack is the pilot deployment baseline. It keeps PostgreSQL, MinIO and ClamAV on an internal network; only the Nginx frontend publishes a host port.

## Start

```bash
cp .env.example .env
# Set a unique bootstrap password and KMP_MODEL_MASTER_KEY from `openssl rand -base64 32`.
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

## Production requirements

- The model master key is mounted as a Docker secret; move database, MinIO, and bootstrap credentials
  to the deployment platform's secret provider before using non-local data.
- Pin and mirror every image by digest after vulnerability review.
- Keep the multi-architecture ClamAV Debian image on a reviewed 1.4.x patch release; the Alpine `clamav/clamav:1.4` tag does not provide an ARM64 manifest.
- Terminate TLS before Nginx and keep secure session cookies enabled.
- Back up PostgreSQL and object storage together; a release is recoverable only when both are restored to a consistent point.
- Restrict `KMP_ALLOWED_MODEL_HOSTS` to reviewed model gateways.
- Configure object-store lifecycle policies for quarantine and failed uploads.
- Do not expose PostgreSQL, MinIO, ClamAV, API or Worker ports to the host network.

The API and Worker are separate images so Worker concurrency can be changed without increasing HTTP replicas. Kubernetes manifests are intentionally outside the first deployment scope.

The baseline keeps `KMP_AGENT_ENABLED=false`; enable the Agent Runtime only after a reviewed model connection and Worker-specific credentials are configured. See [Compose runtime verification](../../docs/operations/runtime-verification.md) for migration, readiness, secret-mount, SBOM, and dependency-convergence checks.
