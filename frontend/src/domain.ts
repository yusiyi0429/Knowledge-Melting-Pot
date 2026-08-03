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

export interface ModelConnection {
  id: string;
  name: string;
  provider: string;
  modelId: string;
  baseUrl: string;
  state: "CONNECTED" | "UNTESTED";
  credentialConfigured: boolean;
}

export interface UserSummary {
  id: string;
  username: string;
  name: string;
  roles: Array<"OPERATOR" | "PUBLISHER" | "ADMIN">;
  state: "ENABLED" | "DISABLED";
  createdAt: string;
}

export interface AuditRecord {
  id: string;
  at: string;
  actor: string;
  actorType: "USER" | "SYSTEM" | "AGENT";
  action: string;
  target: string;
  revision: string;
  traceId: string;
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
  if (["READY", "PUBLISHED", "CONNECTED", "ENABLED"].includes(status)) return "success";
  if (["EXTRACTING", "GENERATING", "ALIGNING"].includes(status)) return "info";
  if (["BLOCKED", "PARTIALLY_PUBLISHED", "UNTESTED"].includes(status)) return "warning";
  if (["FAILED", "DISABLED"].includes(status)) return "danger";
  return "neutral";
}
