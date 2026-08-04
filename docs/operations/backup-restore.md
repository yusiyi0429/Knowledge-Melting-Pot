# Compose 备份与恢复

业务恢复点必须同时包含 PostgreSQL 和 MinIO。数据库保存对象引用、Revision、任务、发布 Manifest 和配置版本；只恢复其中一侧会产生不可追溯或不可下载的数据。

## 创建一致性备份

为现有 Compose 项目选择一个全新的绝对目录，并提供当前项目使用的 MinIO 凭据：

```bash
export KMP_COMPOSE_PROJECT=knowledge-melting-pot
export KMP_BACKUP_DIR=/absolute/path/kmp-backup-20260804T210000Z
export KMP_MINIO_ROOT_PASSWORD='use-the-current-deployment-secret'
scripts/backup-compose.sh
```

脚本执行以下受控过程：

1. 记录当前正在运行的 Web、API、Worker 和 Evaluation Worker。
2. 停止这些写入方，保留 PostgreSQL 与 MinIO 运行，形成单机一致性停写窗口。
3. 以 custom format 执行 `pg_dump`，逻辑镜像四个业务 Bucket。
4. 生成 `metadata.json`、`postgres.dump`、`objects.tar` 和 `SHA256SUMS`。
5. 只重启备份前原本运行的服务。

备份目录必须为空；脚本不会覆盖已有文件。模型连接凭据仍由数据库中的 AES-GCM 密文保存，但解密所需的 `KMP_MODEL_MASTER_KEY` 不会写入备份，必须由部署 Secret 系统独立备份。数据库、MinIO、模型主密钥和镜像/提交版本应属于同一个恢复点记录。

## 恢复到全新项目

恢复脚本拒绝已有容器或卷的项目，避免误覆盖当前环境：

```bash
export KMP_COMPOSE_PROJECT=kmp-restored
export KMP_BACKUP_DIR=/absolute/path/kmp-backup-20260804T210000Z
export KMP_RESTORE_CONFIRM=RESTORE_EMPTY_PROJECT
export KMP_MINIO_ROOT_PASSWORD='target-minio-secret'
export KMP_MODEL_MASTER_KEY='the-original-model-master-key'
scripts/restore-compose.sh
```

恢复前会校验全部 SHA-256；随后创建空 PostgreSQL/MinIO 卷、恢复数据库和四个 Bucket，并默认启动 API、Worker、Web。若只需数据核验，可设置 `KMP_RESTORE_START_APP=false`。目标应运行与备份 Manifest 对应的同版本镜像；恢复完成后至少验证 API readiness、Flyway 历史、Release Manifest、随机素材下载和模型连接的 `credentialConfigured` 状态。

## 自动演练

```bash
scripts/verify-backup-restore.sh
```

演练只使用 `kmp-backup-source-check` 和 `kmp-backup-target-check` 两个隔离项目：创建合成 Scene 和 MinIO 对象，执行真实停写备份，先验证非空项目拒绝恢复且源数据完整，再删除源卷并恢复到全新目标，验证数据库记录、对象正文和 API readiness，最后断言两侧容器与卷均为零。

2026-08-04 本机结果：`BACKUP_OK ... objects=1 services_quiesced=true`、`RESTORE_GUARD_OK non_empty_project_rejected=true source_scene_intact=1`、`RESTORE_OK ... checksums=verified`、`BACKUP_RESTORE_OK scene=1 object=KMP_BACKUP_RESTORE_PROBE_2026 readiness=UP`，最终 `CLEANUP_OK` 的源/目标容器和卷均为 0。
