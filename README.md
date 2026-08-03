# Knowledge Melting Pot

知识萃取智能体工作台把制度、专家经验和标注案例转换为可编辑、可追溯、可发布的知识资产。

## Architecture

- React + TypeScript workbench served behind a same-origin reverse proxy.
- Spring Boot API for authentication, RBAC, REST, SSE, versioning and audit.
- Separate Spring Boot Worker for parsing, extraction and asset-generation jobs.
- PostgreSQL + pgvector for business state and dense retrieval.
- MinIO/S3 for immutable source files, generated assets and release manifests.
- `agent-core-java:0.1.13` isolated behind `workbench-agent-adapter`.

The first runnable slice covers login, scenes/subscenes/rounds, durable jobs, material partition isolation, document revisions, alignment concurrency semantics, five asset states, and cumulative partial releases. Binary ingest/parsing, real knowledge generation, asset renderers, scene exploration and sandboxed Skill execution remain separately versioned capabilities.

## Local development

Prerequisites: JDK 21, Node.js 24, pnpm 11 and Docker with Compose.

```bash
./mvnw -f backend/pom.xml clean verify
pnpm install --frozen-lockfile
pnpm --dir frontend test
pnpm --dir frontend build
docker compose -f deploy/compose/compose.yaml up --build
```

Copy `.env.example` to `.env` only for local use. Never commit credentials. The bootstrap administrator password and model encryption key must be supplied through mounted secret files in production.

部署前按 [Compose 运行时验证](docs/operations/runtime-verification.md) 检查镜像、密钥挂载、Flyway、Spring Session、初始管理员、依赖收敛和 SBOM。
