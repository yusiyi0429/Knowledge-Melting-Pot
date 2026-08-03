# 实施状态与能力边界

更新时间：2026-08-03

本仓库已经形成可构建的 Phase 0/1 基础和一组可审查的业务纵向切片，但不把四阶段、94–122 人周的完整产品伪装为一次性完成。以下状态用于产品验收、迭代排期和部署判断。

## 已实现

- React/Vite 工作台路由和 V11 视觉拆分；登录使用同源 Session/CSRF API，首次登录强制改密；其余业务页面明确标识为离线演示。
- Java 21 / Spring Boot 3.5.3 / Maven 多模块；API 与 Worker 独立进程和镜像。
- `agent-core-java:0.1.13` 只存在于 Worker Adapter；每个 Job 独立 Runtime，Workflow/ReAct 触点、流事件映射和敏感文本脱敏均有契约测试。
- PostgreSQL 持久化 Session、本地账号、组合角色、首次改密、会话撤销和审计；`PUBLISHER` 单独只获得发布权限，内容写操作仍要求 `OPERATOR` 或 `ADMIN`。
- 模型连接和不可变配置版本；凭据只写、AES-256-GCM 信封加密、HTTPS 主机白名单、DNS 地址复核和私网地址拒绝。
- PostgreSQL Job、顺序 JobEvent、`FOR UPDATE SKIP LOCKED`、租约、心跳、attempt、取消、重试和 24 小时幂等记录。
- 公开 Job DTO 不暴露 payload、对象存储引用、请求归属或原始错误；SSE 只输出六种事件和白名单 Envelope，并支持 `Last-Event-ID` 重放。
- Scene 基础管理、SubScene 创建/查询和 ExtractionRound 创建/查询；新 SubScene 自动创建首个 DRAFT Round。
- V5 素材元数据、RoundMaterial 共享/分区绑定、200MB 与格式门禁、不可变触发器；SQL 与应用层双重保证知识流程不读 Holdout、评测只读 Holdout。
- 不可变文档 Revision、ETag、定稿元数据和最低限度 Markdown 定稿门禁（标题与 `[SRC-*]` 锚点）。
- V6 不可变 AlignmentProposal 和独立 adoption 记录；受限 `replaceMarkdown` patch、监管素材门禁、双重 ETag 与并发采纳保护。
- 五类资产独立状态与生成 Job 编排；累计不可变 Release、部分覆盖、历史 carry-forward、缺失项、二次确认和 canonical SHA-256 Manifest。
- Maven 依赖收敛、CycloneDX SBOM、零警告 OpenAPI lint、前端测试/构建、Playwright、三镜像 CI 构建和按完整 commit SHA 固定的 Trivy HIGH/CRITICAL 门禁。
- 独立 Compose 运行门禁已验证 API readiness、Flyway V1–V6、初始管理员、Secure XSRF/Session Cookie、真实 CSRF 登录与 PostgreSQL Session、Worker 稳定启动、无宿主端口、无 SDK 文件日志及一次性 Secret 不落日志；临时容器和卷已清理。

## 明确失败而不是伪成功

- 素材上传目前返回 `DECLARATION_ONLY / OBJECT_STORAGE_NOT_CONFIGURED`，不伪造预签名 URL；complete 只创建 validation-only INGEST Job。
- 未配置对象存储适配器时 INGEST 以 `OBJECT_STORAGE_NOT_CONFIGURED` 失败，不把素材标记为 READY。
- Agent Runtime 关闭时相关任务以 `AGENT_RUNTIME_DISABLED` 失败。
- 即使 Agent Runtime 开启，萃取缺少服务端验证的素材上下文时也以 `MATERIAL_CONTEXT_NOT_READY` 失败，不根据 ID 或用户任意 Prompt 猜测内容。
- 当前模型连接测试只验证安全配置边界，响应明确区分 `networkAttempted=false` 与 `connectivityVerified=false`。

## 尚未实现，不能用于生产验收

- MinIO/S3 预签名分片上传、对象落盘确认、ClamAV、Magic MIME、Zip Slip/压缩炸弹预算、PDF/DOCX/XLSX/TXT 解析、OCR_REQUIRED 和 Chunk 来源定位。
- pgvector 写入与中文稠密检索、Material/Chunk 到 Worker 的可信上下文装配。
- JSON Schema 驱动的 KnowledgeIR、规则一致性/引用完整性校验，以及 Markdown 与 SourceRef 的完整双向解析；当前文档 API 的 `sourceRefs` 为空数组。
- 真实模型驱动的 Map/Reduce 萃取和 AlignmentProposal 生成；现有 Adapter 与 Proposal 采纳语义已经就位，但缺少可消费 READY 素材的生成 Handler。
- 五类资产的实际 XLSX/JSON/Markdown/Mermaid/Skill/JSONL 生成器、对象存储下载、Bundle 和失败项内容级重试。
- 七角色挂载、Skill/模型层级覆盖、Skill Fork、配置导入预览/事务应用及历史 Release 配置回溯。
- 场景探索、隔离 Skill 沙箱、OpenJiuwen 运行接口、真实 Holdout 评测和准确率回流。
- 除认证、首次改密和 SSE Hook 外，前端业务页尚未完整接入真实 REST 数据；页面中的资产与治理数据仍是显式离线演示数据。
- 性能基准、备份恢复演练、本机/同提交 CI 的 Trivy 执行结果和外部安全测评；仓库已配置镜像扫描门禁，但不能把尚未运行的远端 CI 当作通过。

## 下一里程碑顺序

1. 建立对象存储、病毒扫描和解析端口，完成四格式 Golden fixtures，并让 Material 从 `UPLOADED` 安全迁移到 `READY/FAILED`。
2. 持久化 Chunk/SourceRef/Embedding，接通只读 MaterialSelectionPort 与 Worker 的可信上下文组装。
3. 实现 KnowledgeIR Schema、Markdown 双向验证和 Map/Reduce 萃取 Handler。
4. 实现五类确定性生成器、对象下载和 Bundle，完成首个真实部分发布 E2E。
5. 再接入角色/Skill 配置、场景探索、沙箱和真实 Holdout 评测。
