# Compose 运行时验证

本文用于验证 API、Worker、数据库迁移、持久化会话、初始管理员和容器密钥挂载。验证始终使用独立项目名 `kmp-runtime-check`，不得复用生产 Compose 项目或数据卷。

## 前置条件

- Docker Engine 与 Docker Compose v2
- JDK 21（Maven Wrapper 验证需要）
- `openssl`、`curl`、`jq`、`rg`

只在当前 shell 中生成一次性验证凭据，不把值写入仓库或终端输出：

```bash
export KMP_DB_NAME=kmp_runtime_verify
export KMP_DB_USER=kmp_runtime
export KMP_DB_PASSWORD="$(openssl rand -base64 24)"
export KMP_BOOTSTRAP_ADMIN_USERNAME=admin
export KMP_BOOTSTRAP_ADMIN_PASSWORD="$(openssl rand -base64 24)"
export KMP_MODEL_MASTER_KEY="$(openssl rand -base64 32)"
```

仓库提供了等价的自动门禁，会自动生成一次性 Secret、构建镜像、执行本文全部断言并清理独立项目：

```bash
scripts/verify-compose-runtime.sh
```

只有在三张 `kmp-runtime-check-*` 镜像已经由同一源码构建时，才可用
`KMP_RUNTIME_BUILD=false scripts/verify-compose-runtime.sh` 跳过重建。

## Compose 与镜像

先校验最终 Compose 模型，再构建三个交付镜像：

```bash
docker compose -p kmp-runtime-check \
  -f deploy/compose/compose.yaml config --quiet

docker compose -p kmp-runtime-check \
  -f deploy/compose/compose.yaml build api worker web
```

确认渲染结果满足以下约束：

- API 将 `kmp_bootstrap_admin_password` 挂载为 `workbench.bootstrap-admin.password`。
- API 与 Worker 将 `kmp_model_master_key` 挂载到 `/run/secrets/kmp_model_master_key`。
- API 默认设置 `KMP_SECURE_COOKIES=true`；正式部署必须在 Web 前提供 TLS 终止。
- Worker 明确设置 `KMP_AGENT_ENABLED=false`。
- Worker 默认启用受限 PDF OCR，并设置页数、像素、单页超时和输出字符预算。
- API/Worker/Evaluation Worker 以 UID 10001 运行且没有可写 Home；Web 以 `nginx` 非 root 用户和只读根文件系统运行。相关服务统一移除 Linux capabilities、启用 `no-new-privileges`，并设置 PID、CPU、内存和有界 `tmpfs`。
- API 是唯一 Flyway 迁移所有者；Worker 设置 `SPRING_FLYWAY_ENABLED=false`，并等待 API healthy 后启动。
- PostgreSQL、MinIO、ClamAV、API 和 Worker 均未发布宿主机端口；只有 Web 在本地验证时发布 `8088`。

运行时镜像的 API readiness healthcheck 使用镜像内置的 `wget`。升级 `eclipse-temurin` 前须重新执行：

```bash
docker run --rm --entrypoint /bin/sh eclipse-temurin:21-jre \
  -c 'command -v wget && java -version'
```

Worker 镜像必须同时包含 Tesseract 引擎和简体中文语言数据；CI 也执行相同检查：

```bash
docker run --rm --entrypoint tesseract knowledge-melting-pot-worker:ocr-check --version
docker run --rm --entrypoint tesseract knowledge-melting-pot-worker:ocr-check --list-langs \
  | grep -Fx chi_sim
```

OCR 只由扫描型 PDF 的解析诊断触发，不接受用户命令。默认上限为 100 页、2 亿像素、单页 45 秒和 500 万输出字符，可通过 `KMP_OCR_*` 环境变量向下收紧。Worker 会将 `OCR_STARTED`、逐页 `OCR_PROCESSING` 与 `OCR_COMPLETED` 持久化为 JobEvent，浏览器通过现有 Job SSE 接收并支持断线重放。

2026-08-04 本机从最终 Worker 镜像验证 `tesseract 5.5.0`，语言清单包含 `chi_sim/eng/osd`；使用与适配器相同的 `chi_sim+eng --dpi 200 --psm 6` 参数识别本机合成扫描图，输入与输出均为“风险审核”。该结果证明镜像运行时和中文数据可用，不代表复杂业务扫描件的质量基准。

## 启动与数据库证据

启动依赖、API、禁用 Agent Runtime 的 Worker 和同源反向代理：

```bash
docker compose -p kmp-runtime-check \
  -f deploy/compose/compose.yaml up -d --no-build api worker web

api_id="$(docker compose -p kmp-runtime-check -f deploy/compose/compose.yaml ps -q api)"
worker_id="$(docker compose -p kmp-runtime-check -f deploy/compose/compose.yaml ps -q worker)"
postgres_id="$(docker compose -p kmp-runtime-check -f deploy/compose/compose.yaml ps -q postgres)"
```

等待 `api` 变为 `healthy`，然后执行不读取凭据正文的检查：

```bash
docker exec "$api_id" wget -qO- \
  http://localhost:8080/actuator/health/readiness | jq -c '{status}'

docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  "SELECT version || ':' || success FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8','9','10','11','12','13') ORDER BY installed_rank;"

docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('spring_session','spring_session_attributes');"

docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  "SELECT (COUNT(*) = 1) || ':' || COALESCE(bool_and(must_change_password), false) FROM app_user WHERE lower(username)=lower('admin');"

docker inspect "$worker_id" --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | rg '^(KMP_AGENT_ENABLED=false|SPRING_FLYWAY_ENABLED=false)$'
docker logs "$worker_id" 2>&1 | rg 'Started WorkbenchWorkerApplication'
docker exec "$worker_id" sh -c 'test ! -e /app/logs'
docker inspect "$api_id" --format '{{json .HostConfig.PortBindings}}'
docker inspect "$worker_id" --format '{{json .HostConfig.PortBindings}}'
```

预期迁移输出为 `1:t` 到 `16:t`，会话表计数为 `2`，管理员检查为 `true:true`，两个端口检查均为 `{}`。V12 应存在七条最新角色模板，V13 应存在六张探索/通知相关表，V15/V16 应分别存在模型连通验证和 Embedding 激活指针结构。Worker 必须稳定运行且不得创建 SDK 默认的 `/app/logs`。

再通过 Web 反向代理执行真实 CSRF + Session 登录，并确认两类 Cookie 都带 `Secure`。现代浏览器和 curl 将 localhost 视为安全来源；其他纯 HTTP 调试地址必须显式设置 `KMP_SECURE_COOKIES=false`，不得把该覆盖用于正式部署。请求正文经 stdin 传递，不把密码拼进 curl 参数；Cookie jar 使用临时文件：

```bash
cookie_jar="$(mktemp)"
csrf_headers="$(mktemp)"
csrf_json="$(curl -fsS -D "$csrf_headers" -c "$cookie_jar" http://localhost:8088/api/v1/auth/csrf)"
rg -i '^set-cookie: XSRF-TOKEN=.*; Secure;' "$csrf_headers"
csrf_header="$(printf '%s' "$csrf_json" | jq -er '.headerName')"
csrf_token="$(printf '%s' "$csrf_json" | jq -er '.token')"

login_headers="$(mktemp)"
login_status="$({
  printf '{"username":"%s","password":"%s"}' \
    "$KMP_BOOTSTRAP_ADMIN_USERNAME" "$KMP_BOOTSTRAP_ADMIN_PASSWORD"
} | curl -sS -D "$login_headers" -o /dev/null -w '%{http_code}' \
  -b "$cookie_jar" -c "$cookie_jar" \
  -H "$csrf_header: $csrf_token" \
  -H 'Content-Type: application/json' \
  --data-binary @- http://localhost:8088/api/v1/auth/login)"
test "$login_status" = 204
rg -i '^set-cookie: KMP_SESSION=.*; Secure;' "$login_headers"

docker exec "$postgres_id" psql -U "$KMP_DB_USER" -d "$KMP_DB_NAME" -Atc \
  'SELECT COUNT(*) FROM spring_session;'
rm -f "$cookie_jar" "$csrf_headers" "$login_headers"
```

登录后的 Session 行数预期为 `1`。最后对 API 与 Worker 日志执行不回显的精确泄漏检查：

```bash
for secret_value in \
  "$KMP_DB_PASSWORD" \
  "$KMP_BOOTSTRAP_ADMIN_PASSWORD" \
  "$KMP_MODEL_MASTER_KEY"
do
  for container_id in "$api_id" "$worker_id"
  do
    if docker logs "$container_id" 2>&1 | rg -F --quiet -- "$secret_value"; then
      echo "Secret value found in container logs" >&2
      exit 1
    fi
  done
done
```

检查日志时不得开启 shell tracing，也不得打印或搜索后回显凭据值。

验证结束后，只清理该临时项目创建的容器和数据卷：

```bash
docker compose -p kmp-runtime-check \
  -f deploy/compose/compose.yaml down --volumes --remove-orphans
unset KMP_DB_NAME KMP_DB_USER KMP_DB_PASSWORD
unset KMP_BOOTSTRAP_ADMIN_USERNAME KMP_BOOTSTRAP_ADMIN_PASSWORD KMP_MODEL_MASTER_KEY
```

清理后，`docker ps -a --filter label=com.docker.compose.project=kmp-runtime-check -q` 和
`docker volume ls --filter label=com.docker.compose.project=kmp-runtime-check -q` 都必须为空。

## 最近一次本机证据

2026-08-04 使用一次性 Secret 和独立项目 `kmp-runtime-check` 重新构建三张镜像并执行仓库脚本通过：输出 `RUNTIME_OK readiness=UP`，Flyway V1–V16 全部成功、存在七条角色模板及六张探索/通知相关表，初始管理员唯一且要求首次改密，XSRF 与 Session Cookie 均带 `Secure`，真实 CSRF 登录返回 204 并写入一条 Spring Session；Worker 稳定运行、Agent Runtime 与 Worker Flyway 均关闭、没有 `/app/logs`，API/Worker 无宿主端口，日志未命中三项一次性 Secret。容器门禁同时检查非 root、`cap_drop=ALL`、`no-new-privileges`、有界 `/tmp`、Web 只读根文件系统和 CSP/防点击劫持响应头。验证结束后输出 `CLEANUP_OK containers=0 volumes=0`。

## 性能基线

`scripts/verify-performance.sh` 使用独立项目 `kmp-performance-check` 和 JDK-only 有界负载器，不引入生产依赖。它通过最终 Nginx→API→PostgreSQL Session 路径请求 `/api/v1/auth/csrf`，默认 300 次、并发 16、错误率 0、p95 上限 1500 ms，并在结束后删除隔离容器和卷：

```bash
scripts/verify-performance.sh
```

2026-08-04 最终本机复测输出 `PERFORMANCE_RESULT requests=300 concurrency=16 failures=0 error_rate=0.0000 p50_ms=14 p95_ms=91 p99_ms=94 throughput_rps=387.91`，随后 `PERFORMANCE_OK` 与 `CLEANUP_OK containers=0 volumes=0`。该结果是单机平台基线，不代表模型供应商延迟、OCR 吞吐或生产容量规划；正式容量结论必须使用目标硬件和脱敏业务分布重新执行。

## 备份恢复

`scripts/backup-compose.sh` 在停止写入方后同步备份 PostgreSQL 与四个 MinIO Bucket，并生成 SHA-256 清单；`scripts/restore-compose.sh` 只接受全新空项目和显式确认。完整说明见 [Compose 备份与恢复](backup-restore.md)。

```bash
scripts/verify-backup-restore.sh
```

2026-08-04 隔离演练通过：非空项目首先被 `RESTORE_GUARD_OK` 拒绝且原数据保持完整；删除源项目后，数据库合成 Scene、MinIO 对象和恢复后 API readiness 均一致，校验和全部通过，源/目标项目的容器和卷最终均为 0。

## Embedding 与中文稠密检索

`scripts/verify-vector-retrieval.sh` 使用临时 pgvector 数据库和本地 API 进程，不依赖外部模型密钥，验证 V1–V16 迁移、不可变 Embedding Profile 激活、Profile 专属 HNSW 索引、中文排序和 Holdout 物理隔离：

```bash
scripts/verify-vector-retrieval.sh
```

2026-08-04 本机输出 `VECTOR_RETRIEVAL_OK migrations=16 profile_active=true dimension=3 hnsw_index=true ranking=SRC-KNOWLEDGE-RISK,SRC-KNOWLEDGE-ROUTINE holdout_excluded=true`。该验收通过合成向量检查索引、排序和分区约束；Provider 真实 Embedding 响应由单独的连接测试和需要用户密钥的 E2E 门禁承担。

## KnowledgeIR、萃取与对齐闭环

`scripts/verify-knowledge-workflow.sh` 使用专属 Compose 项目 `kmp-knowledge-check`、独立端口和一次性数据卷，生成一份本地 TXT 监管素材并验证：

```bash
scripts/verify-knowledge-workflow.sh
```

- 真实预签名分片上传、MinIO、ClamAV、Tika/TXT 解析与 READY Chunk。
- 创建 Scene 级知识萃取挂载，通过配置导入 Preview/Apply 创建对齐挂载，并验证 Global→Scene→SubScene 有效配置解析。
- 仅提交 Round ID 启动萃取，由服务端解析并冻结模型/Skill/Agent 配置版本和哈希；持久化 Map、Reduce、KnowledgeIR 与 SourceRef。
- 无结构块的 Markdown 返回 422 且不新增 Revision。
- 监管 AlignmentProposal 含结构化 Diff；旧 ETag 采纳返回 412，正确 ETag 生成新 Revision 和不可变 adoption 记录。
- 将同一合成素材放入探索 staging，运行 `SCENE_EXPLORE`，以 ETag 采纳候选后创建正式 Scene/SubScene/Round；断言复用原 material/blob/chunk，跨场景搜索命中且终态 Job 生成用户通知。
- 数据库断言 Flyway V1–V14、七角色模板、挂载/导入应用、萃取与对齐 Job 配置快照、两份 Revision 投影、Holdout 零混入、探索采纳、通知、Release 绑定评测及独立 Evaluation Worker 领取记录；退出时断言容器和卷均为零。

该门禁的知识流程通过显式 `workbench.agent.test-stub-enabled=true` 使用确定性本地 Adapter；评测流程由独立 `evaluation-worker` 调用 `SANDBOX_V1` 声明式分类器。它验证编排、持久化、安全边界和协议，不调用外部模型，也不代表模型质量。沙箱负向门禁会验证脚本载荷被拒绝、外部网络与只读文件系统写入失败、`cap_drop=ALL`、`no-new-privileges` 以及容器环境不含应用 Secret。

2026-08-04 最新运行输出 `KNOWLEDGE_WORKFLOW_OK ... migrations=...14:true ... proposal=ADOPTED release=<sha256> evaluation=SUCCEEDED:3:3:1.000000:3/3 accuracy=1.000000 exploration=ACCEPTED blob_reused=t search=true notification=1`，随后输出 `CLEANUP_OK containers=0 volumes=0`。其中 3/3 是合成样本对受控声明式分类器的确定性协议验收，不是外部模型准确率证明。

## 崩溃续跑与同哈希去重破坏性演练

独立的 `scripts/verify-destructive-recovery.sh` 在专属 Compose 项目 `kmp-destructive-check` 中执行两个真实破坏性场景，全程不触碰 `kmp-validation`、`kmp-runtime-check` 或任何其他栈，退出时只清理该项目自己的容器和数据卷：

```bash
scripts/verify-destructive-recovery.sh
```

- RECOVERY：上传约 7MB 合成 TXT 触发 INGEST，Worker 处理中 `docker kill --signal=KILL` 并删除容器，再 `up` 出一个全新 Worker；新 Worker 在短租约（PT15S）到期后重领租约并幂等续跑。终态断言：job `SUCCEEDED`、`attempt=2`、存在两次 started 事件、`material_blob` 恰一行、`material_chunk` 恰一组（行数 = ceil(行数/100)）、Material `READY`、MinIO 验证桶恰一个对象。
- DEDUP：启动第二个 Worker（`worker-b`），上传两份字节相同（不同文件名）的 TXT；两个 Worker 并发摄取。终态断言 started 事件来自两个不同 Worker，同一 sha256 只有一个 blob、一份 chunk、一个 MinIO 对象，两个 Material 均 `READY` 且共享同一 `blob_id`。
- Embedding 未配置门禁：断言 job 事件包含稳定 `EMBEDDING_PROVIDER_UNCONFIGURED`、`chunk_embedding` 零行、且不存在 `EMBEDDINGS_COMMITTED` 伪成功事件。

成功输出以 `RECOVERY_OK`、`DEDUP_OK`、`EMBEDDING_UNCONFIGURED_OK` 结尾，清理后输出 `CLEANUP_OK containers=0 volumes=0`。脚本使用 `deploy/compose/destructive-override.yaml`（短租约、单并发、第二 Worker、MinIO 主机端口 19000/19001），该覆盖文件不得用于生产或共享栈。

2026-08-04 以最新源码重新构建 API、Worker、Worker-B 和 Web 镜像后通过：`RECOVERY_OK` 显示 `attempt=2`、`started_events=2`、700 个 Chunk；`DEDUP_OK` 显示 `workers=2`、一个 Blob、700 个 Chunk、两个 READY Material 和一个对象；Embedding 诊断出现 3 次且向量表为 0 行。最终输出 `DESTRUCTIVE_OK` 与 `CLEANUP_OK containers=0 volumes=0`。

## 依赖收敛与 SBOM

依赖收敛是强门禁，覆盖 Maven Reactor 的所有模块：

```bash
./mvnw -f backend/pom.xml \
  org.apache.maven.plugins:maven-enforcer-plugin:3.5.0:enforce \
  -Denforcer.rules=dependencyConvergence -DskipTests
```

生成 CycloneDX JSON 与 XML，并确认输出存在：

```bash
./mvnw -f backend/pom.xml \
  org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DskipTests
test -s backend/target/bom.json
test -s backend/target/bom.xml
```

CI 会上传 `backend/target/bom.json` 和 `backend/target/bom.xml`，保留 14 天。SBOM 是构建证据，不包含运行时 Secret。
