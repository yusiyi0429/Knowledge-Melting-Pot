export type AuthenticatedUser = {
  id: string;
  username: string;
  displayName: string;
  enabled: boolean;
  roles: Array<"OPERATOR" | "PUBLISHER" | "ADMIN">;
  mustChangePassword: boolean;
};

type CsrfMetadata = {
  headerName: string;
  parameterName: string;
  token: string;
};

export type ProblemFieldError = {
  field: string;
  message: string;
};

type Problem = {
  detail?: string;
  code?: string;
  traceId?: string;
  errors?: ProblemFieldError[];
};

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly traceId?: string;
  readonly errors?: ProblemFieldError[];

  constructor(message: string, status: number, problem?: Problem) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = problem?.code;
    this.traceId = problem?.traceId;
    this.errors = problem?.errors;
  }
}

async function readProblem(response: Response): Promise<Problem | undefined> {
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/problem+json") && !contentType.includes("application/json")) {
    return undefined;
  }
  try {
    return (await response.json()) as Problem;
  } catch {
    return undefined;
  }
}

async function requireSuccess(response: Response, fallback: string): Promise<Response> {
  if (response.ok) return response;
  const problem = await readProblem(response);
  throw new ApiError(problem?.detail || fallback, response.status, problem);
}

async function csrf(): Promise<CsrfMetadata> {
  const response = await requireSuccess(
    await fetch("/api/v1/auth/csrf", { credentials: "same-origin", cache: "no-store" }),
    "无法获取安全令牌。",
  );
  const metadata = (await response.json()) as CsrfMetadata;
  if (!/^[A-Za-z0-9-]{1,64}$/.test(metadata.headerName) || !metadata.token) {
    throw new ApiError("服务端返回了无效的安全令牌。", 502);
  }
  return metadata;
}

async function postWithCsrf(path: string, body: unknown): Promise<Response> {
  return mutateWithCsrf(path, "POST", body);
}

async function mutateWithCsrf(path: string, method: "POST" | "PUT" | "PATCH" | "DELETE", body?: unknown): Promise<Response> {
  const token = await csrf();
  const headers: Record<string, string> = { [token.headerName]: token.token };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  return fetch(path, {
    method,
    credentials: "same-origin",
    cache: "no-store",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function mutateWithCsrfHeaders(
  path: string,
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  body: unknown,
  extraHeaders: Record<string, string>,
): Promise<Response> {
  const token = await csrf();
  return fetch(path, {
    method,
    credentials: "same-origin",
    cache: "no-store",
    headers: { "Content-Type": "application/json", [token.headerName]: token.token, ...extraHeaders },
    body: JSON.stringify(body),
  });
}

export async function getCurrentUser(): Promise<AuthenticatedUser> {
  return getJson("/api/v1/auth/me", "已登录，但无法读取当前账号。");
}

export async function login(username: string, password: string): Promise<AuthenticatedUser> {
  await requireSuccess(
    await postWithCsrf("/api/v1/auth/login", { username, password }),
    "登录失败，请检查账号或稍后重试。",
  );
  return getCurrentUser();
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await requireSuccess(
    await postWithCsrf("/api/v1/auth/password", { currentPassword, newPassword }),
    "密码修改失败，请检查当前密码和密码策略。",
  );
}

export type ModelProvider = "OPENAI_COMPATIBLE" | "DASHSCOPE";

export type ModelConnectionValidationStatus = "UNTESTED" | "CONNECTIVITY_VERIFIED";

export interface ModelConnection {
  id: string;
  name: string;
  provider: ModelProvider;
  baseUrl: string;
  enabled: boolean;
  credentialConfigured: boolean;
  validationStatus: ModelConnectionValidationStatus;
  lastValidatedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ModelConnectionDraft {
  name: string;
  provider: ModelProvider;
  baseUrl: string;
  credential?: string;
  enabled: boolean;
}

export interface ModelConnectionUpdate extends ModelConnectionDraft {
  clearCredential: boolean;
}

export interface ModelConfigVersion {
  id: string;
  modelConnectionId: string;
  version: number;
  modelId: string;
  temperature: number;
  maxOutputTokens: number;
  createdAt: string;
}

export interface ModelConfigVersionDraft {
  modelId: string;
  temperature: number;
  maxOutputTokens: number;
}

export interface ModelConnectionTestResult {
  status: "CONNECTED" | "FAILED";
  networkAttempted: boolean;
  connectivityVerified: boolean;
  credentialConfigured: boolean;
  messageCode: string;
  testedAt: string;
}

async function getJson<T>(path: string, fallback: string): Promise<T> {
  const response = await requireSuccess(
    await fetch(path, { credentials: "same-origin", cache: "no-store" }),
    fallback,
  );
  return (await response.json()) as T;
}

/** The credential is write-only: empty or whitespace-only values are never sent to the server. */
function withCredential<T extends ModelConnectionDraft>(draft: T): Omit<T, "credential"> | T {
  if (draft.credential !== undefined && draft.credential.trim().length === 0) {
    const { credential: _omitted, ...rest } = draft;
    return rest as Omit<T, "credential">;
  }
  return draft;
}

export async function listModelConnections(): Promise<ModelConnection[]> {
  return getJson("/api/v1/model-connections", "无法读取模型连接列表。");
}

export async function createModelConnection(draft: ModelConnectionDraft): Promise<ModelConnection> {
  const response = await requireSuccess(
    await postWithCsrf("/api/v1/model-connections", withCredential(draft)),
    "创建模型连接失败。",
  );
  return (await response.json()) as ModelConnection;
}

export async function updateModelConnection(
  id: string,
  update: ModelConnectionUpdate,
): Promise<ModelConnection> {
  const response = await requireSuccess(
    await mutateWithCsrf(`/api/v1/model-connections/${id}`, "PUT", withCredential(update)),
    "更新模型连接失败。",
  );
  return (await response.json()) as ModelConnection;
}

export async function deleteModelConnection(id: string): Promise<void> {
  await requireSuccess(
    await mutateWithCsrf(`/api/v1/model-connections/${id}`, "DELETE"),
    "删除模型连接失败。",
  );
}

export async function testModelConnection(id: string): Promise<ModelConnectionTestResult> {
  const response = await requireSuccess(
    await postWithCsrf(`/api/v1/model-connections/${id}/connection-tests`, {}),
    "连接测试失败。",
  );
  return (await response.json()) as ModelConnectionTestResult;
}

export async function listModelConfigVersions(connectionId: string): Promise<ModelConfigVersion[]> {
  return getJson(`/api/v1/model-connections/${connectionId}/config-versions`, "无法读取配置版本。");
}

export async function createModelConfigVersion(
  connectionId: string,
  draft: ModelConfigVersionDraft,
): Promise<ModelConfigVersion> {
  const response = await requireSuccess(
    await postWithCsrf(`/api/v1/model-connections/${connectionId}/config-versions`, draft),
    "创建配置版本失败。",
  );
  return (await response.json()) as ModelConfigVersion;
}

export type AgentRole =
  | "SCENE_EXPLORER"
  | "KNOWLEDGE_EXTRACTOR"
  | "ALIGNMENT_REVIEWER"
  | "RULE_CATALOG_GENERATOR"
  | "DECISION_FLOW_GENERATOR"
  | "SKILL_PACKAGER"
  | "QA_EVALUATOR";

export type AgentMountScope = "GLOBAL" | "SCENE" | "SUB_SCENE";

export interface AgentRoleDefinition {
  role: AgentRole;
  displayName: string;
  stage: string;
  description: string;
}

export interface AgentMountVersion {
  id: string;
  role: AgentRole;
  scope: AgentMountScope;
  scopeId: string | null;
  version: number;
  templateVersionId: string | null;
  enabled: boolean | null;
  modelConfigVersionId: string | null;
  skillVersionId: string | null;
  optionsJson: string | null;
  configHash: string;
  createdBy: string;
  createdAt: string;
}

export interface AgentScopeConfiguration {
  scope: AgentMountScope;
  scopeId: string | null;
  sceneId: string | null;
  etag: string;
  mounts: AgentMountVersion[];
}

export interface EffectiveAgentConfiguration {
  role: AgentRole;
  displayName: string;
  stage: string;
  enabled: boolean;
  configured: boolean;
  modelConfigVersionId: string | null;
  skillVersionId: string | null;
  optionsJson: string;
  effectiveHash: string;
  effectiveMountVersionId: string | null;
  enabledSource: AgentMountScope | null;
  modelSource: AgentMountScope | null;
  skillSource: AgentMountScope | null;
  optionsSource: AgentMountScope | "TEMPLATE" | null;
  lineage: AgentMountVersion[];
}

export interface AgentModelCatalogEntry {
  versionId: string;
  connectionId: string;
  connectionName: string;
  provider: string;
  version: number;
  modelId: string;
  temperature: number;
  maxOutputTokens: number;
}

export interface AgentSkillCatalogEntry {
  versionId: string;
  skillId: string;
  name: string;
  kind: "TEMPLATE" | "INSTANCE";
  sceneId: string | null;
  version: number;
  packageHash: string;
}

export interface AgentConfigurationCatalog {
  models: AgentModelCatalogEntry[];
  skills: AgentSkillCatalogEntry[];
}

export interface AgentMountDraft {
  role: AgentRole;
  enabled: boolean | null;
  modelConfigVersionId: string | null;
  skillVersionId: string | null;
  options: Record<string, unknown> | null;
}

export interface ConfigurationImportPreview {
  id: string;
  schemaVersion: string;
  scope: AgentMountScope;
  scopeId: string | null;
  sceneId: string | null;
  baseEtag: string;
  manifestJson: string;
  manifestHash: string;
  diffJson: string;
  createdBy: string;
  createdAt: string;
  appliedBy: string | null;
  appliedAt: string | null;
  applied: boolean;
}

export async function listAgentRoles(): Promise<AgentRoleDefinition[]> {
  return getJson("/api/v1/agent-roles", "无法读取智能体角色。 ");
}

export async function getAgentScope(scope: AgentMountScope, scopeId: string | null): Promise<AgentScopeConfiguration> {
  const query = new URLSearchParams({ scope });
  if (scopeId) query.set("scopeId", scopeId);
  return getJson(`/api/v1/agent-mounts?${query}`, "无法读取作用域配置。");
}

export async function getEffectiveAgentConfigurations(
  sceneId: string,
  subSceneId: string | null,
): Promise<EffectiveAgentConfiguration[]> {
  const query = new URLSearchParams({ sceneId });
  if (subSceneId) query.set("subSceneId", subSceneId);
  return getJson(`/api/v1/agent-mounts/effective?${query}`, "无法解析有效智能体配置。");
}

export async function getAgentConfigurationCatalog(): Promise<AgentConfigurationCatalog> {
  return getJson("/api/v1/agent-configuration-catalog", "无法读取模型与 Skill 版本目录。");
}

export async function appendAgentMount(
  scope: AgentMountScope,
  scopeId: string | null,
  etag: string,
  draft: AgentMountDraft,
): Promise<AgentScopeConfiguration> {
  const response = await requireSuccess(
    await mutateWithCsrfHeaders(
      "/api/v1/agent-mounts/versions",
      "POST",
      { scope, scopeId, ...draft },
      { "If-Match": etag },
    ),
    "保存智能体配置版本失败。",
  );
  return (await response.json()) as AgentScopeConfiguration;
}

export async function previewConfigurationImport(
  scope: AgentMountScope,
  scopeId: string | null,
  roles: AgentMountDraft[],
): Promise<ConfigurationImportPreview> {
  const response = await requireSuccess(
    await postWithCsrf("/api/v1/configuration-imports/previews", { scope, scopeId, roles }),
    "配置导入校验失败。",
  );
  return (await response.json()) as ConfigurationImportPreview;
}

export async function applyConfigurationImport(
  importId: string,
  manifestHash: string,
): Promise<AgentScopeConfiguration> {
  const response = await requireSuccess(
    await mutateWithCsrfHeaders(
      `/api/v1/configuration-imports/${importId}/apply`,
      "POST",
      {},
      { "If-Match": manifestHash },
    ),
    "应用配置导入失败。",
  );
  return (await response.json()) as AgentScopeConfiguration;
}

export type UserRole = "OPERATOR" | "PUBLISHER" | "ADMIN";

/** Same DTO as the session user; the managed-account list never contains credential material. */
export type UserAccount = AuthenticatedUser;

export interface CreateUserDraft {
  username: string;
  displayName: string;
  initialPassword: string;
  roles: UserRole[];
}

export interface UpdateUserPatch {
  displayName?: string;
  enabled?: boolean;
  roles?: UserRole[];
}

export async function listUsers(): Promise<UserAccount[]> {
  return getJson("/api/v1/users", "无法读取用户列表。");
}

export async function createUser(draft: CreateUserDraft): Promise<UserAccount> {
  const response = await requireSuccess(
    await postWithCsrf("/api/v1/users", draft),
    "创建用户失败。",
  );
  return (await response.json()) as UserAccount;
}

export async function updateUser(id: string, patch: UpdateUserPatch): Promise<UserAccount> {
  const response = await requireSuccess(
    await mutateWithCsrf(`/api/v1/users/${id}`, "PATCH", patch),
    "更新用户失败。",
  );
  return (await response.json()) as UserAccount;
}

export async function resetUserPassword(id: string, newPassword: string): Promise<void> {
  await requireSuccess(
    await postWithCsrf(`/api/v1/users/${id}/password-reset`, { newPassword }),
    "重置密码失败。",
  );
}

export interface Scene {
  id: string;
  name: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface ScenePage {
  items: Scene[];
  page: number;
  size: number;
  total: number;
}

export interface CreateSceneDraft {
  name: string;
  description?: string;
}

export async function listScenes(page: number, size: number): Promise<ScenePage> {
  return getJson(`/api/v1/scenes?page=${page}&size=${size}`, "无法读取场景列表。");
}

export async function createScene(draft: CreateSceneDraft): Promise<Scene> {
  const response = await requireSuccess(
    await postWithCsrf("/api/v1/scenes", draft),
    "创建场景失败。",
  );
  return (await response.json()) as Scene;
}

export interface UpdateSceneDraft {
  name: string;
  description?: string;
}

export async function getScene(id: string): Promise<Scene> {
  return getJson(`/api/v1/scenes/${id}`, "无法读取场景。");
}

export async function updateScene(id: string, draft: UpdateSceneDraft): Promise<Scene> {
  const response = await requireSuccess(
    await mutateWithCsrf(`/api/v1/scenes/${id}`, "PUT", draft),
    "保存场景失败。",
  );
  return (await response.json()) as Scene;
}

export interface SubScene {
  id: string;
  sceneId: string;
  name: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSubSceneDraft {
  name: string;
  description?: string;
}

export async function listSubScenes(sceneId: string): Promise<SubScene[]> {
  return getJson(`/api/v1/scenes/${sceneId}/subscenes`, "无法读取子场景。");
}

export async function createSubScene(sceneId: string, draft: CreateSubSceneDraft): Promise<SubScene> {
  const response = await requireSuccess(
    await postWithCsrf(`/api/v1/scenes/${sceneId}/subscenes`, draft),
    "创建子场景失败。",
  );
  return (await response.json()) as SubScene;
}

export type ExtractionRoundStatus = "DRAFT" | "PROCESSING" | "READY" | "FAILED" | "SUPERSEDED";

export interface ExtractionRound {
  id: string;
  subSceneId: string;
  roundNumber: number;
  status: ExtractionRoundStatus;
  createdAt: string;
  updatedAt: string;
}

export async function listExtractionRounds(sceneId: string): Promise<ExtractionRound[]> {
  return getJson(`/api/v1/scenes/${sceneId}/rounds`, "无法读取萃取轮次。");
}

export async function createExtractionRound(sceneId: string, subSceneId: string): Promise<ExtractionRound> {
  const response = await requireSuccess(
    await postWithCsrf(`/api/v1/scenes/${sceneId}/rounds`, { subSceneId }),
    "创建轮次失败。",
  );
  return (await response.json()) as ExtractionRound;
}

export type MaterialPartition = "SOURCE" | "LABELED_TRAIN" | "LABELED_HOLDOUT";
export type MaterialShareScope = "ROUND" | "SUBSCENE" | "SCENE";
export type MaterialStatus = "PENDING_UPLOAD" | "UPLOADED" | "SCANNING" | "READY" | "FAILED" | "INACTIVE";

export interface MaterialBinding {
  id: string;
  roundId: string;
  subSceneId: string;
  partition: MaterialPartition;
  shareScope: MaterialShareScope;
  regulatorySource: boolean;
  active: boolean;
}

export interface MaterialListItem {
  id: string;
  fileName: string;
  format: "PDF" | "DOCX" | "XLSX" | "TXT";
  mediaType: string;
  sizeBytes: number;
  status: MaterialStatus;
  createdAt: string;
  updatedAt: string;
  binding: MaterialBinding;
}

export interface PresignedPartMetadata {
  partNumber: number;
  url: string;
  headers: Record<string, string>;
}

export interface UploadIntent {
  id: string;
  materialId: string;
  objectKey: string;
  materialStatus: MaterialStatus;
  uploadMode: "DECLARATION_ONLY" | "MULTIPART_PRESIGNED";
  capabilityStatus: "OBJECT_STORAGE_NOT_CONFIGURED" | "MULTIPART_PRESIGNED";
  uploadUrlAvailable: boolean;
  maxBytes: number;
  supportedFormats: string[];
  completionBehavior: string;
  messageCode: string;
  parts: PresignedPartMetadata[];
  partSize: number | null;
  partCount: number | null;
  presignedUrls: string[];
}

export interface CreateUploadIntentDraft {
  fileName: string;
  sizeBytes: number;
  mediaType: string;
  sha256: string;
  roundId?: string | null;
  explorationSessionId?: string;
  subSceneIds: string[];
  partition: MaterialPartition;
  shareScope: MaterialShareScope;
  regulatorySource: boolean;
}

export type ExplorationStatus = "DRAFT" | "ANALYZING" | "READY" | "ACCEPTED" | "FAILED" | "CANCELLED";

export interface ExplorationSession {
  id: string;
  title: string;
  status: ExplorationStatus;
  exploreJobId: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ExplorationMaterial {
  id: string;
  fileName: string;
  format: "PDF" | "DOCX" | "XLSX" | "TXT";
  sizeBytes: number;
  status: MaterialStatus;
  createdAt: string;
  updatedAt: string;
}

export interface ExplorationCandidate {
  id: string;
  rank: number;
  sceneName: string;
  sceneDescription: string;
  subSceneName: string;
  subSceneDescription: string;
  rationale: string;
  valueLevel: "HIGH" | "MEDIUM" | "LOW";
  estimatedRuleCount: number;
  estimatedFlowCount: number;
  tags: string[];
  materialIds: string[];
}

export interface ExplorationAcceptance {
  candidateId: string;
  sceneId: string;
  subSceneId: string;
  roundId: string;
  acceptedAt: string;
}

export interface ExplorationDetail {
  session: ExplorationSession;
  etag: string;
  materials: ExplorationMaterial[];
  candidates: ExplorationCandidate[];
  acceptance: ExplorationAcceptance | null;
}

export interface ExplorationAcceptanceResult {
  sceneId: string;
  subSceneId: string;
  roundId: string;
  reusedMaterialIds: string[];
}

export async function listExplorations(): Promise<ExplorationSession[]> {
  return getJson("/api/v1/explorations", "无法读取场景探索记录。");
}

export async function createExploration(title: string): Promise<ExplorationSession> {
  const response = await requireSuccess(
    await postWithCsrf("/api/v1/explorations", { title }),
    "创建场景探索失败。",
  );
  return (await response.json()) as ExplorationSession;
}

export async function getExploration(id: string): Promise<ExplorationDetail> {
  return getJson(`/api/v1/explorations/${id}`, "无法读取场景探索详情。");
}

export async function startExploration(id: string, idempotencyKey: string): Promise<JobAccepted> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(`/api/v1/explorations/${id}/analysis-jobs`, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey, [token.headerName]: token.token },
      body: "{}",
    }),
    "启动场景探索失败。",
  );
  return (await response.json()) as JobAccepted;
}

export async function acceptExplorationCandidate(
  sessionId: string,
  candidateId: string,
  etag: string,
): Promise<ExplorationAcceptanceResult> {
  const response = await requireSuccess(
    await mutateWithCsrfHeaders(
      `/api/v1/explorations/${sessionId}/candidates/${candidateId}/accept`,
      "POST",
      {},
      { "If-Match": etag },
    ),
    "接受候选场景失败。",
  );
  return (await response.json()) as ExplorationAcceptanceResult;
}

export interface SearchResult {
  type: "SCENE" | "RULE" | "SOURCE";
  sceneId: string;
  subSceneId: string | null;
  resourceId: string;
  title: string;
  excerpt: string;
}

export async function searchWorkbench(query: string, limit = 20): Promise<SearchResult[]> {
  const params = new URLSearchParams({ q: query, limit: String(limit) });
  return getJson(`/api/v1/search?${params}`, "搜索失败。");
}

export interface UserNotification {
  id: string;
  type: string;
  title: string;
  message: string;
  resourceType: string;
  resourceId: string;
  createdAt: string;
  readAt: string | null;
  read: boolean;
}

export interface NotificationInbox {
  unreadCount: number;
  items: UserNotification[];
}

export async function getNotificationInbox(limit = 30): Promise<NotificationInbox> {
  return getJson(`/api/v1/notifications?limit=${limit}`, "无法读取任务通知。");
}

export async function markNotificationRead(id: string): Promise<void> {
  await requireSuccess(
    await mutateWithCsrf(`/api/v1/notifications/${id}/read`, "PATCH"),
    "更新通知失败。",
  );
}

export async function markAllNotificationsRead(): Promise<void> {
  await requireSuccess(
    await mutateWithCsrf("/api/v1/notifications/read", "PATCH"),
    "更新通知失败。",
  );
}

export interface UploadedPart {
  partNumber: number;
  etag: string;
}

export interface MaterialJobAccepted {
  jobId: string;
  status: string;
  statusUrl: string;
  eventsUrl: string;
}

export type JobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface Job {
  id: string;
  type: string;
  status: JobStatus;
  stage: string | null;
  percent: number;
  attempt: number;
  errorCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface JobAccepted {
  jobId: string;
  status: string;
  statusUrl: string;
  eventsUrl: string;
}

export async function startExtraction(
  subSceneId: string,
  roundId: string,
  modelConfigVersionId: string,
  skillVersionId: string,
  idempotencyKey: string,
): Promise<JobAccepted> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(`/api/v1/subscenes/${subSceneId}/extraction-jobs`, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [token.headerName]: token.token,
      },
      body: JSON.stringify({ roundId, modelConfigVersionId, skillVersionId }),
    }),
    "发起知识萃取失败。",
  );
  return (await response.json()) as JobAccepted;
}

export async function listWorkbenchMaterials(roundId: string, subSceneId: string): Promise<MaterialListItem[]> {
  return getJson(`/api/v1/materials?roundId=${roundId}&subSceneId=${subSceneId}`, "无法读取素材列表。");
}

export async function createUploadIntent(
  draft: CreateUploadIntentDraft,
  idempotencyKey: string,
): Promise<UploadIntent> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch("/api/v1/materials/upload-intents", {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [token.headerName]: token.token,
      },
      body: JSON.stringify(draft),
    }),
    "创建上传任务失败。",
  );
  return (await response.json()) as UploadIntent;
}

export async function completeUpload(intentId: string, parts: UploadedPart[]): Promise<MaterialJobAccepted> {
  const response = await requireSuccess(
    await postWithCsrf(`/api/v1/materials/upload-intents/${intentId}/complete`, { parts }),
    "提交上传完成失败。",
  );
  return (await response.json()) as MaterialJobAccepted;
}

export async function abortUpload(intentId: string): Promise<void> {
  await requireSuccess(
    await mutateWithCsrf(`/api/v1/materials/upload-intents/${intentId}`, "DELETE"),
    "中止上传失败。",
  );
}

export async function getJob(jobId: string): Promise<Job> {
  return getJson(`/api/v1/jobs/${jobId}`, "无法读取任务状态。");
}

export interface SourceRefEntry {
  code: string;
  materialId: string;
  materialSha256: string;
  chunkId: string;
  locatorType: "PDF_PAGE_PARAGRAPH" | "DOCX_PARAGRAPH" | "DOCX_TABLE_CELL" | "XLSX_RANGE" | "TXT_LINES";
  page: number | null;
  paragraph: number | null;
  table: number | null;
  sheet: string | null;
  rowStart: number | null;
  rowEnd: number | null;
  colStart: number | null;
  colEnd: number | null;
  lineStart: number | null;
  lineEnd: number | null;
  excerptHash: string;
}

export interface KnowledgeDocument {
  id: string;
  subSceneId: string;
  revisionId: string;
  revisionNumber: number;
  contentMd: string;
  contentHash: string;
  finalized: boolean;
  sourceRefs: SourceRefEntry[];
  etag: string;
}

export interface SaveDocumentDraft {
  subSceneId?: string;
  contentMd: string;
  revisionNote?: string;
  finalize: boolean;
}

export interface DocumentRevisionSummary {
  id: string;
  revisionNumber: number;
  contentHash: string;
  note: string | null;
  createdBy: string;
  finalized: boolean;
  createdAt: string;
}

export async function getKnowledgeDocument(documentId: string): Promise<KnowledgeDocument> {
  return getJson(`/api/v1/knowledge-documents/${documentId}`, "无法读取知识文档。");
}

export async function saveKnowledgeDocument(
  documentId: string,
  draft: SaveDocumentDraft,
  ifMatch: string,
): Promise<KnowledgeDocument> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(`/api/v1/knowledge-documents/${documentId}`, {
      method: "PUT",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "If-Match": ifMatch,
        [token.headerName]: token.token,
      },
      body: JSON.stringify(draft),
    }),
    "保存知识文档失败。",
  );
  return (await response.json()) as KnowledgeDocument;
}

export async function listDocumentRevisions(documentId: string): Promise<DocumentRevisionSummary[]> {
  return getJson(`/api/v1/knowledge-documents/${documentId}/revisions`, "无法读取修订历史。");
}

export type AlignmentAction = "CONSISTENCY" | "REGULATORY" | "GAP_ANALYSIS" | "REWRITE";

export interface KnowledgeDiff {
  addedRuleIds: string[];
  removedRuleIds: string[];
  changedRuleIds: string[];
  addedFlowIds: string[];
  removedFlowIds: string[];
  changedFlowIds: string[];
  sourceRefDelta: number;
}

export interface AlignmentProposal {
  id: string;
  documentId: string;
  baseRevisionId: string;
  baseEtag: string;
  action: AlignmentAction;
  status: "READY" | "ADOPTED";
  structuredPatch: {
    operation: "replaceKnowledgeIr";
    replacement: unknown;
    diff: KnowledgeDiff;
  };
  reason: string;
  sourceRefs: SourceRefEntry[];
  regulatoryMaterialIds: string[];
  createdBy: string;
  createdAt: string;
  adoptedRevisionId: string | null;
  adoptedBy: string | null;
  adoptedAt: string | null;
}

export async function startAlignment(
  documentId: string,
  baseRevisionId: string,
  action: AlignmentAction,
  regulatoryMaterialIds: string[],
  idempotencyKey: string,
): Promise<JobAccepted> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(`/api/v1/knowledge-documents/${documentId}/alignment-jobs`, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [token.headerName]: token.token,
      },
      body: JSON.stringify({ baseRevisionId, action, regulatoryMaterialIds }),
    }),
    "发起 AI 对齐失败。",
  );
  return (await response.json()) as JobAccepted;
}

export async function listAlignmentProposals(documentId: string): Promise<AlignmentProposal[]> {
  return getJson(`/api/v1/knowledge-documents/${documentId}/alignment-proposals`, "无法读取对齐提案。");
}

export async function adoptAlignmentProposal(proposalId: string, baseEtag: string): Promise<KnowledgeDocument> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(`/api/v1/alignment-proposals/${proposalId}/adopt`, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: { "If-Match": baseEtag, [token.headerName]: token.token },
    }),
    "采纳对齐提案失败。",
  );
  return (await response.json()) as KnowledgeDocument;
}

export type AssetType = "RULE_CATALOG" | "DECISION_FLOW" | "SKILL_PACKAGE" | "QA_PAIRS" | "EVALUATION_SET";
export type AssetStatus = "PENDING" | "GENERATING" | "READY" | "FAILED" | "SUPERSEDED" | "BLOCKED";

export interface Asset {
  id: string;
  subSceneId: string;
  type: AssetType;
  version: number;
  status: AssetStatus;
  documentRevisionId: string | null;
  objectKey: string;
  checksum: string;
  failureReason: string;
  createdAt: string;
  updatedAt: string;
}

export interface AssetJobAccepted {
  jobId: string;
  statusUrl: string;
  eventsUrl: string;
  status: string;
}

export async function listSubSceneAssets(subSceneId: string): Promise<Asset[]> {
  return getJson(`/api/v1/subscenes/${subSceneId}/assets`, "无法读取资产列表。");
}

export async function generateAssets(
  subSceneId: string,
  documentRevisionId: string,
  types: AssetType[],
  idempotencyKey: string,
): Promise<AssetJobAccepted> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(`/api/v1/subscenes/${subSceneId}/asset-generation-jobs`, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [token.headerName]: token.token,
      },
      body: JSON.stringify({ documentRevisionId, types }),
    }),
    "发起资产生成失败。",
  );
  return (await response.json()) as AssetJobAccepted;
}

export interface ReleaseValidation {
  ready: boolean;
  coverage: "PARTIAL" | "FULL";
  baseReleaseId: string | null;
  selected: string[];
  carriedForward: string[];
  missing: string[];
  blockers: string[];
  warnings: string[];
}

export interface ReleaseDraft {
  tag: string;
  selectedSubSceneIds: string[];
  note: string;
  confirmed: boolean;
  expectedBaseReleaseId: string | null;
}

export interface Release {
  id: string;
  sceneId: string;
  tag: string;
  coverage: "PARTIAL" | "FULL";
  note: string;
  previousReleaseId: string | null;
  manifestSha256: string;
  createdAt: string;
}

export async function validateRelease(sceneId: string, draft: ReleaseDraft): Promise<ReleaseValidation> {
  const response = await requireSuccess(
    await postWithCsrf(`/api/v1/scenes/${sceneId}/release-validations`, draft),
    "发布预检失败。",
  );
  return (await response.json()) as ReleaseValidation;
}

export async function createRelease(
  sceneId: string,
  draft: ReleaseDraft,
  idempotencyKey: string,
): Promise<Release> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(`/api/v1/scenes/${sceneId}/releases`, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [token.headerName]: token.token,
      },
      body: JSON.stringify(draft),
    }),
    "发布失败。",
  );
  return (await response.json()) as Release;
}

export async function getReleaseManifest(releaseId: string): Promise<string> {
  const response = await requireSuccess(
    await fetch(`/api/v1/releases/${releaseId}/manifest`, { credentials: "same-origin", cache: "no-store" }),
    "无法读取发布清单。",
  );
  return response.text();
}

/** The current release baseline for cumulative releases; null when none published yet. */
export async function getLatestRelease(sceneId: string): Promise<Release | null> {
  const response = await fetch(`/api/v1/scenes/${sceneId}/releases/latest`, {
    credentials: "same-origin",
    cache: "no-store",
  });
  if (response.status === 404) return null;
  await requireSuccess(response, "无法读取发布基线。");
  return (await response.json()) as Release;
}

export type EvaluationStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type EvaluationOutcome = "PASSED" | "FAILED" | "ERROR";

export interface EvaluationRun {
  id: string;
  releaseId: string;
  subSceneId: string;
  roundId: string;
  documentRevisionId: string;
  evaluationAssetId: string;
  skillAssetId: string;
  modelConfigVersionId: string;
  skillVersionId: string;
  jobId: string;
  caseSetHash: string;
  status: EvaluationStatus;
  totalCases: number;
  passedCases: number;
  failedCases: number;
  errorCases: number;
  accuracy: number | null;
  failureCode: string;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

export interface EvaluationCase {
  id: string;
  ordinal: number;
  caseKey: string;
  input: string;
  expected: string;
  materialId: string;
  chunkId: string;
  sourceRefCode: string;
  tags: string[];
  prediction: string | null;
  outcome: EvaluationOutcome | null;
  errorCode: string | null;
  latencyMillis: number | null;
}

export interface EvaluationDetail {
  run: EvaluationRun;
  cases: EvaluationCase[];
}

export interface EvaluationAccepted extends JobAccepted {
  evaluationRunId: string;
}

export async function startReleaseEvaluation(
  releaseId: string,
  subSceneId: string,
  roundId: string,
  idempotencyKey: string,
): Promise<EvaluationAccepted> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(`/api/v1/releases/${releaseId}/subscenes/${subSceneId}/evaluation-jobs`, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [token.headerName]: token.token,
      },
      body: JSON.stringify({ roundId }),
    }),
    "发起留出集评测失败。",
  );
  return (await response.json()) as EvaluationAccepted;
}

export async function listEvaluationRuns(releaseId: string, subSceneId: string): Promise<EvaluationRun[]> {
  return getJson(
    `/api/v1/releases/${releaseId}/subscenes/${subSceneId}/evaluation-runs`,
    "无法读取评测记录。",
  );
}

export async function getEvaluationRun(runId: string): Promise<EvaluationDetail> {
  return getJson(`/api/v1/evaluation-runs/${runId}`, "无法读取评测证据。 ");
}

export interface AuditEvent {
  id: string;
  actorId: string;
  action: string;
  targetType: string;
  targetId: string;
  detailsJson: string;
  traceId: string | null;
  occurredAt: string;
}

export async function listAuditEvents(page: number, size: number): Promise<AuditEvent[]> {
  return getJson(`/api/v1/audit-events?page=${page}&size=${size}`, "无法读取审计记录。");
}

export type SkillKind = "TEMPLATE" | "INSTANCE";

export interface Skill {
  id: string;
  name: string;
  kind: SkillKind;
  description: string;
  sceneId: string | null;
  sourceSkillId: string | null;
  sourceSkillVersionId: string | null;
  version: number | null;
  packageHash: string | null;
  manifestJson: string | null;
  createdAt: string;
}

export interface SkillVersion {
  id: string;
  skillId: string;
  version: number;
  manifestJson: string;
  packageHash: string;
  createdBy: string;
  createdAt: string;
}

export interface SkillDetail {
  id: string;
  name: string;
  kind: SkillKind;
  description: string;
  sceneId: string | null;
  sourceSkillId: string | null;
  sourceSkillVersionId: string | null;
  createdAt: string;
  versions: SkillVersion[];
}

export interface CreateSkillDraft {
  name: string;
  description?: string;
  manifest: string;
  packageHash: string;
}

export async function listSkills(kind?: SkillKind, sceneId?: string): Promise<Skill[]> {
  const query = new URLSearchParams();
  if (kind) query.set("kind", kind);
  if (sceneId) query.set("sceneId", sceneId);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return getJson(`/api/v1/skills${suffix}`, "无法读取 Skill 列表。");
}

export async function getSkill(skillId: string): Promise<SkillDetail> {
  return getJson(`/api/v1/skills/${skillId}`, "无法读取 Skill。");
}

export async function createSkill(draft: CreateSkillDraft, idempotencyKey: string): Promise<Skill> {
  return postSkill(`/api/v1/skills`, draft, idempotencyKey, "创建模板失败。");
}

export async function forkSkillInstance(
  skillId: string,
  sceneId: string,
  idempotencyKey: string,
): Promise<Skill> {
  return postSkill(`/api/v1/skills/${skillId}/instances`, { sceneId }, idempotencyKey, "创建场景实例失败。");
}

export async function createSkillVersion(
  skillId: string,
  manifest: string,
  packageHash: string,
  idempotencyKey: string,
): Promise<SkillVersion> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(`/api/v1/skills/${skillId}/versions`, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [token.headerName]: token.token,
      },
      body: JSON.stringify({ manifest, packageHash }),
    }),
    "创建 Skill 版本失败。",
  );
  return (await response.json()) as SkillVersion;
}

async function postSkill(path: string, body: unknown, idempotencyKey: string, fallback: string): Promise<Skill> {
  const token = await csrf();
  const response = await requireSuccess(
    await fetch(path, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [token.headerName]: token.token,
      },
      body: JSON.stringify(body),
    }),
    fallback,
  );
  return (await response.json()) as Skill;
}
