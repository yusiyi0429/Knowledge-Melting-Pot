export type Tone = "neutral" | "info" | "success" | "warning" | "danger" | "purple";

export type SceneStatus = "ALIGNING" | "EXTRACTING" | "PARTIALLY_PUBLISHED" | "PUBLISHED";

export interface SceneSummary {
  id: string;
  name: string;
  description: string;
  status: SceneStatus;
  statusLabel: string;
  round: string;
  subsceneCount: number;
  materialCount: number;
  assetCount: number;
  updatedAt: string;
  owner: string;
}

export type MaterialPartition = "SOURCE" | "LABELED_TRAIN" | "LABELED_HOLDOUT";

export interface Material {
  id: string;
  name: string;
  size: string;
  kind: "PDF" | "DOCX" | "XLSX" | "TXT";
  tag: string;
  partition: MaterialPartition;
  locator: string;
  status: "READY" | "PARSING" | "FAILED";
}

export interface Subscene {
  id: string;
  name: string;
  description: string;
  revision: string;
  releaseState: "READY" | "BLOCKED" | "PUBLISHED";
}

export interface SourceRef {
  id: string;
  source: string;
  locator: string;
  excerpt: string;
}

export type AssetState = "READY" | "GENERATING" | "BLOCKED" | "FAILED";

export interface Asset {
  id: string;
  name: string;
  format: string;
  description: string;
  state: AssetState;
  version: string;
  sourceRevision: string;
  detail?: string;
}

export interface JobEvent {
  eventId: string;
  sequence: number;
  jobId: string;
  type: "stage-started" | "progress" | "preview" | "warning" | "completed" | "failed";
  stage: string;
  percent: number;
  messageCode: string;
  message: string;
  traceId: string;
  createdAt: string;
}

export interface AgentConfig {
  id: string;
  stage: string;
  name: string;
  trigger: string;
  description: string;
  skill: string;
  model: string;
  optionLabel: string;
  option: string;
  version: string;
}

export interface SkillSummary {
  id: string;
  name: string;
  description: string;
  kind: "TEMPLATE" | "INSTANCE";
  version: string;
  parent?: string;
  scene?: string;
  packageHash: string;
}

export const ALLOWED_AUDIT_DETAIL_KEYS = new Set([
  "revision", "revisionNumber", "contentHash", "hash", "jobId", "tag", "sceneId", "documentId",
  "manifestHash", "manifestSha256", "version", "roundNumber", "modelConnectionId",
  "credentialConfigured", "enabled", "status", "validationStatus", "networkAttempted",
  "connectivityVerified", "partCount",
]);

export type SafeAuditDetail = { key: string; value: string };

/**
 * Parses server audit details and returns only allowlisted scalar metadata.
 * Unknown keys, nested objects, and malformed JSON are never surfaced.
 */
export function safeAuditDetails(detailsJson: string): SafeAuditDetail[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(detailsJson);
  } catch {
    return [];
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return [];
  }
  const details: SafeAuditDetail[] = [];
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (!ALLOWED_AUDIT_DETAIL_KEYS.has(key)) continue;
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
      details.push({ key, value: String(value) });
    }
  }
  return details.sort((a, b) => a.key.localeCompare(b.key));
}

export const partitionLabels: Record<MaterialPartition, string> = {
  SOURCE: "事实素材",
  LABELED_TRAIN: "标注训练",
  LABELED_HOLDOUT: "留出评测",
};

export function releaseCanInclude(subscene: Subscene, assets: Asset[]): boolean {
  return subscene.releaseState !== "BLOCKED" && assets.every((asset) => asset.state === "READY");
}

export function toStatusTone(status: string): Tone {
  if (["READY", "PUBLISHED", "CONNECTED", "ENABLED", "CONNECTIVITY_VERIFIED"].includes(status)) return "success";
  if (["EXTRACTING", "GENERATING", "ALIGNING", "SCANNING"].includes(status)) return "info";
  if (["BLOCKED", "PARTIALLY_PUBLISHED", "UNTESTED"].includes(status)) return "warning";
  if (["FAILED", "DISABLED"].includes(status)) return "danger";
  return "neutral";
}

export const MATERIAL_MAX_BYTES = 200 * 1024 * 1024;

/**
 * Canonical media type is derived from the allowed file extension, never from
 * the browser-supplied File.type. Returns null for unsupported extensions.
 */
export function mediaTypeForFile(fileName: string): string | null {
  const extension = fileName.split(".").pop()?.toLowerCase() ?? "";
  switch (extension) {
    case "pdf": return "application/pdf";
    case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    case "txt": return "text/plain";
    default: return null;
  }
}

export function validateMaterialFile(file: { name: string; size: number }): string | null {
  if (mediaTypeForFile(file.name) === null) {
    return "仅支持 PDF / DOCX / XLSX / TXT；.doc 与 .xls 不受支持。";
  }
  if (file.size === 0) {
    return "不能上传 0 字节文件。";
  }
  if (file.size > MATERIAL_MAX_BYTES) {
    return "文件不能超过 200MB。";
  }
  return null;
}

export const MATERIAL_STATUS_LABELS: Record<string, string> = {
  PENDING_UPLOAD: "待上传",
  UPLOADED: "已上传",
  SCANNING: "校验中",
  READY: "已就绪",
  FAILED: "校验失败",
  INACTIVE: "已失效",
};

export const ASSET_TYPE_LABELS: Record<string, string> = {
  RULE_CATALOG: "规则清单",
  DECISION_FLOW: "研判流程",
  SKILL_PACKAGE: "Skill 包",
  QA_PAIRS: "QA 对",
  EVALUATION_SET: "评测集",
};

export const ASSET_STATUS_LABELS: Record<string, string> = {
  PENDING: "待生成",
  GENERATING: "生成中",
  READY: "已就绪",
  FAILED: "失败",
  SUPERSEDED: "已取代",
  BLOCKED: "前置缺失",
};

export const ASSET_TYPE_DESCRIPTIONS: Record<string, string> = {
  RULE_CATALOG: "规则清单 JSON 与 XLSX，来自定稿文档。",
  DECISION_FLOW: "可审计研判流程 MD / JSON / Mermaid，不含推理过程。",
  SKILL_PACKAGE: "只读 Skill 资源包（SKILL.md / prompt / schema / manifest）。",
  QA_PAIRS: "问答对 JSONL 与去重 / 锚点校验报告。",
  EVALUATION_SET: "仅留出分区安全元数据，绝不包含正文。",
};

export function modelProviderLabel(provider: string): string {
  return provider === "DASHSCOPE" ? "DashScope" : "OpenAI 兼容";
}

export function connectionValidationLabel(status: string): string {
  return status === "CONNECTIVITY_VERIFIED" ? "连通已验证" : "未验证";
}

export interface ConnectionTestSummary {
  label: string;
  note: string;
}

export function connectionTestSummary(result: {
  networkAttempted: boolean;
  connectivityVerified: boolean;
}): ConnectionTestSummary {
  const network = result.networkAttempted ? "已发起网络请求" : "未发起网络请求";
  const connectivity = result.connectivityVerified ? "已确认 Provider 与凭据可用" : "未能验证 Provider 连通性";
  return {
    label: result.connectivityVerified ? "连通已验证" : "连接测试未通过",
    note: `${network}；${connectivity}`,
  };
}
