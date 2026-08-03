# Repository Guidelines

## Structure

- `frontend/` contains the React and TypeScript workbench.
- `backend/` is a Java 21 Maven multi-module project. Only `workbench-agent-adapter` may depend on `agent-core-java`.
- `contracts/openapi.yaml` is the public HTTP contract.
- `deploy/compose/` contains the private single-host deployment.
- `docs/product/originals/` preserves the approved V11 prototype and business document.

## Working Agreements

- Use Chinese for product copy and documentation; keep code identifiers and API fields in English.
- Keep API DTOs and domain types independent from `agent-core-java` dynamic `Map`/`Object` types.
- Treat releases, document revisions, Skill versions, assets, and audit events as immutable.
- Never log source documents, prompts, model responses, credentials, or session cookies.
- Do not execute uploaded Skill scripts outside the dedicated sandbox milestone.
- Preserve the original HTML and DOCX and verify their SHA-256 values after any archival operation.

## Commands

- Backend: `./mvnw -f backend/pom.xml clean verify`
- Frontend: `pnpm install --frozen-lockfile`, then `pnpm --dir frontend test`, `pnpm --dir frontend build`, and `pnpm --dir frontend test:e2e`
- Local stack: `docker compose -f deploy/compose/compose.yaml up --build`

Use small, reviewable changes. Do not commit, push, add production dependencies, or modify the sibling `agent-core-java` repository unless explicitly requested.
