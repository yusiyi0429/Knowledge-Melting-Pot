# 通用脚本沙箱开放决策

- 状态：已决策
- 日期：2026-08-04
- 结论：当前版本不开放通用 Shell、Python、JavaScript、WASM、二进制或用户自定义命令执行；继续支持 `RESOURCE_ONLY` 和受限声明式 `SANDBOX_V1`。

## 当前允许的边界

| 模式 | 输入能力 | 是否执行用户代码 | 结论 |
|---|---|---:|---|
| `RESOURCE_ONLY` | Prompt、Schema、规则与资源元数据 | 否 | 保持开放 |
| `SANDBOX_V1` | `CLASSIFY_CONTAINS` 声明式规则，最多 100 条规则、每条 20 个 token | 否 | 保持开放，仅用于发布绑定的留出集评测 |
| 通用脚本 | Shell、Python、JavaScript、WASM、二进制、任意命令或动态依赖 | 是 | 不开放 |

`SANDBOX_V1` 的进程无宿主端口、无外部网络、无应用 Secret，使用非 root 用户、只读根文件系统、`cap_drop=ALL`、`no-new-privileges`、PID/CPU/内存上限和有界临时目录。Worker 只向固定内部端点发送声明式 `program` 与未标注输入，不发送凭据、模型标签或期望答案。Manifest 校验器和运行时均拒绝未知字段、脚本、命令、外部 URL 和非 `CLASSIFY_CONTAINS` 程序。

## 威胁模型与否决理由

通用脚本必须按主动恶意输入处理，包括用户上传、第三方包和模型生成代码。主要风险是容器逃逸、敏感数据读取或外传、持久化、横向移动、资源耗尽、依赖安装阶段攻击，以及利用解析器或运行时缺陷跨越任务边界。

普通容器的 namespaces、capabilities、seccomp、只读文件系统和 cgroups 是必要的纵深防御，但仍共享宿主 Linux 内核。gVisor 的官方安全说明也明确指出，仅使用 Linux 内核隔离原语时，工作负载仍直接暴露于同一个内核攻击面；为不可信代码引入独立应用内核或硬件虚拟化边界可显著减少该风险。[gVisor 安全简介](https://gvisor.dev/docs/architecture_guide/intro/)、[gVisor 安全模型](https://gvisor.dev/docs/architecture_guide/security/)、[Docker Rootless 模式](https://docs.docker.com/engine/security/rootless/)

当前部署目标是单机 Docker Compose，尚未提供每次调用独立的强隔离运行时、脚本制品审查与签名、离线依赖供应链、细粒度出站代理、任务级身份和完整逃逸测试。因此，直接把现有声明式容器改造成通用解释器不满足开放条件。

## 重新评审的强制门禁

只有同时满足以下条件，才可以提出新的开放决策；默认仍为拒绝：

1. 每次调用使用独立的 gVisor、Kata Containers 或微虚拟机级隔离实例，不复用运行进程，不使用 Docker socket、宿主目录或特权容器。
2. 默认无网络；确有业务需要时只能通过按域名和协议审计的出站代理，DNS、重定向和解析后地址均重新校验。
3. 根文件系统不可变，只挂载只读输入和有容量上限的一次性输出；不注入数据库、MinIO、模型、部署或用户 Secret。
4. 固定非 root 身份，清空环境变量，使用精简 syscall/LSM 策略，并设置 CPU、内存、PID、文件数、磁盘、输出字节和墙钟超时硬上限。
5. 只允许审核过的运行时镜像和锁定依赖；禁止运行时联网安装包，脚本制品必须不可变、哈希绑定、签名并关联提交者与审批记录。
6. API 使用独立的版本化脚本制品模型，不接受命令字符串；发布 Manifest 必须记录代码、运行时、依赖、策略和输入输出哈希。
7. 审计记录创建、审批、执行、终止、资源预算和策略结果，但不记录正文、Secret 或未脱敏输出。
8. 通过容器/运行时逃逸、符号链接与路径穿越、fork bomb、磁盘/内存耗尽、网络旁路、DNS 重绑定、供应链和跨任务数据泄漏专项测试，并由独立安全评审签字。

## 重新评审触发条件

出现不可由声明式 DSL 表达且已确认的业务用例，并且已经选定强隔离运行平台、安全责任人、容量预算和渗透测试范围时，才重新评审。仅因为某个 Skill 包含脚本、模型能够生成代码或普通容器已经启用安全选项，不构成开放理由。

## 当前验收证据

2026-08-04 本机执行 `SkillManifestValidatorTest` 通过 8/8，覆盖 Shell、Python、脚本字段、外部 URL、Secret、未知执行模式、深层嵌套和超限 Manifest 拒绝。`scripts/verify-knowledge-workflow.sh` 重新构建真实 Compose 环境并通过：脚本载荷返回 422，沙箱无外部网络、根文件系统不可写、能力移除、启用 `no-new-privileges` 且无应用 Secret；Release 绑定 Holdout 评测 3/3 成功。演练结束输出 `CLEANUP_OK containers=0 volumes=0`。
