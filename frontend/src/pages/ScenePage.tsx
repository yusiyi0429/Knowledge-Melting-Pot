import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { LineageRail, type LineageStage } from "../components/LineageRail";
import { Button, EmptyState, Glyph, Status } from "../components/Ui";
import { OperationReadinessNotice } from "../components/OperationReadinessNotice";
import {
  ApiError,
  adoptAlignmentProposal,
  createExtractionRound,
  createRelease,
  createSubScene,
  deactivateMaterialBinding,
  generateAssets,
  getAgentConfigurationCatalog,
  getCurrentUser,
  getEvaluationRun,
  getJob,
  getKnowledgeDocument,
  getLatestRelease,
  getOperationReadiness,
  getReleaseManifest,
  getScene,
  listDocumentRevisions,
  listEvaluationRuns,
  listJobAgentExecutions,
  listAlignmentProposals,
  listExtractionRounds,
  listSubSceneAssets,
  listSubScenes,
  listWorkbenchMaterials,
  retrieveKnowledgeChunks,
  retryJob,
  saveKnowledgeDocument,
  startAlignment,
  startExtraction,
  startReleaseEvaluation,
  updateScene,
  validateRelease,
} from "../lib/api";
import type {
  Asset,
  AlignmentAction,
  AlignmentProposal,
  AgentExecutionAttempt,
  AgentModelCatalogEntry,
  AgentSkillCatalogEntry,
  AssetJobAccepted,
  AssetType,
  AuthenticatedUser,
  DocumentRevisionSummary,
  DenseRetrievalResult,
  EvaluationAccepted,
  EvaluationDetail,
  EvaluationRun,
  ExtractionRound,
  ExtractionRoundStatus,
  Job,
  JobAccepted,
  KnowledgeDocument,
  MaterialJobAccepted,
  MaterialListItem,
  OperationReadiness,
  ReadinessOperation,
  Release,
  ReleaseValidation,
  SaveDocumentDraft,
  Scene,
  SourceRefEntry,
  SubScene,
} from "../lib/api";
import { ASSET_STATUS_LABELS, ASSET_TYPE_DESCRIPTIONS, ASSET_TYPE_LABELS, MATERIAL_STATUS_LABELS, partitionLabels, toStatusTone } from "../domain";
import { UploadMaterialDialog } from "./UploadMaterialDialog";
import { useJobEvents } from "../hooks/useJobEvents";
import type { JobEvent } from "../domain";

const steps = [
  { id: 1, title: "场景与素材", detail: "目标 · 素材 · 子场景", stage: "materials" },
  { id: 2, title: "知识萃取与对齐", detail: "萃取 · Revision · 来源", stage: "extraction" },
  { id: 3, title: "知识生成及发布", detail: "五类资产 · 发布快照", stage: "assets" },
] as const;

const ROUND_STATUS_LABELS: Record<ExtractionRoundStatus, string> = {
  DRAFT: "草稿",
  PROCESSING: "处理中",
  READY: "就绪",
  FAILED: "失败",
  SUPERSEDED: "已取代",
};

const EVALUATION_STATUS_LABELS: Record<EvaluationRun["status"], string> = {
  QUEUED: "排队中",
  RUNNING: "评测中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
};

const MATERIAL_EVENT_LABELS: Record<string, string> = {
  JOB_QUEUED: "等待 Worker 领取",
  JOB_STARTED: "开始安全校验",
  HEAD_VERIFIED: "对象元数据已校验",
  HASH_VERIFIED: "SHA-256 已核验",
  MIME_VERIFIED: "文件类型已核验",
  MALWARE_CLEAN: "恶意软件扫描通过",
  ARCHIVE_BUDGET_VERIFIED: "处理预算已核验",
  PARSED: "正文解析完成",
  OCR_STARTED: "扫描件已进入 OCR",
  OCR_COMPLETED: "OCR 文本已生成",
  OCR_PROCESSING: "正在识别扫描页",
  CHUNKS_COMMITTED: "来源 Chunk 已持久化",
  EMBEDDINGS_WRITING: "正在生成稠密向量",
  EMBEDDINGS_COMMITTED: "向量索引数据已提交",
  OBJECT_VERIFYING: "正在复核归档对象",
  JOB_COMPLETED: "素材已就绪",
};

const MATERIAL_FAILURE_LABELS: Record<string, string> = {
  MIME_MISMATCH: "文件类型校验失败：内容与扩展名不一致",
  SHA256_MISMATCH: "文件完整性校验失败",
  SIZE_MISMATCH: "文件大小与上传声明不一致",
  MALWARE_DETECTED: "文件未通过恶意内容扫描",
  OCR_REQUIRED: "扫描件需要 OCR，但当前未启用",
};

function materialEventLabel(event: JobEvent): string {
  if (event.type === "failed") return MATERIAL_FAILURE_LABELS[event.messageCode]
    ?? `处理失败 · ${event.messageCode}`;
  return MATERIAL_EVENT_LABELS[event.messageCode]
    ?? MATERIAL_EVENT_LABELS[event.stage]
    ?? event.stage;
}

function fieldErrorsToRecord(errors: ApiError["errors"]): Record<string, string> {
  const record: Record<string, string> = {};
  for (const error of errors ?? []) {
    if (!record[error.field]) record[error.field] = error.message;
  }
  return record;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatSourceLocator(ref: SourceRefEntry): string {
  switch (ref.locatorType) {
    case "PDF_PAGE_PARAGRAPH": return `PDF 第 ${ref.page ?? "?"} 页 · 段落 ${ref.paragraph ?? "?"}`;
    case "DOCX_PARAGRAPH": return `DOCX 段落 ${ref.paragraph ?? "?"}`;
    case "DOCX_TABLE_CELL": return `DOCX 表 ${ref.table ?? "?"} · 行 ${ref.rowStart ?? "?"} · 列 ${ref.colStart ?? "?"}`;
    case "XLSX_RANGE": return `XLSX ${ref.sheet ?? "?"} · 行 ${ref.rowStart ?? "?"}-${ref.rowEnd ?? "?"}`;
    case "TXT_LINES": return `TXT 行 ${ref.lineStart ?? "?"}-${ref.lineEnd ?? "?"}`;
  }
}

function formatRetrievalLocator(result: DenseRetrievalResult): string {
  switch (result.locatorType) {
    case "PDF_PAGE_PARAGRAPH": return `PDF 第 ${result.page ?? "?"} 页 · 段落 ${result.paragraph ?? "?"}`;
    case "DOCX_PARAGRAPH": return `DOCX 段落 ${result.paragraph ?? "?"}`;
    case "DOCX_TABLE_CELL": return `DOCX 表 ${result.table ?? "?"} · 行 ${result.rowStart ?? "?"} · 列 ${result.colStart ?? "?"}`;
    case "XLSX_RANGE": return `XLSX ${result.sheet ?? "?"} · 行 ${result.rowStart ?? "?"}-${result.rowEnd ?? "?"}`;
    case "TXT_LINES": return `TXT 行 ${result.lineStart ?? "?"}-${result.lineEnd ?? "?"}`;
  }
}

function AddSubSceneDialog({
  saving,
  formError,
  formFieldErrors,
  onClose,
  onSubmit,
}: {
  saving: boolean;
  formError: string | null;
  formFieldErrors: Record<string, string>;
  onClose: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (!node.open) node.showModal();
    return () => {
      if (node.open) node.close();
    };
  }, []);

  return (
    <dialog ref={ref} className="scene-dialog" aria-labelledby="subscene-dialog-title" onCancel={onClose}>
      <form className="model-dialog__form" onSubmit={onSubmit} noValidate>
        <header className="model-dialog__head">
          <div>
            <h2 id="subscene-dialog-title">添加子场景</h2>
            <p>子场景决定萃取范围；名称必填。</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={saving}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          <label className="field">
            <span>子场景名称</span>
            <input name="name" autoFocus autoComplete="off" maxLength={200}
              placeholder="例如：逾期天数与分类下迁" aria-invalid={Boolean(formFieldErrors.name)} />
            <small>1–200 字符。</small>
            {formFieldErrors.name ? <small className="field-error">{formFieldErrors.name}</small> : null}
          </label>
          <label className="field">
            <span>描述（可选）</span>
            <textarea name="description" maxLength={10000}
              placeholder="说明该子场景的业务边界和期望产物。" aria-invalid={Boolean(formFieldErrors.description)} />
            {formFieldErrors.description ? <small className="field-error">{formFieldErrors.description}</small> : null}
          </label>
          {formError ? <div className="form-error" role="alert">{formError}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={saving}>取消</Button>
          <Button type="submit" className="button--primary" disabled={saving}>
            {saving ? "创建中…" : "创建子场景"}
          </Button>
        </footer>
      </form>
    </dialog>
  );
}

export function ScenePage({ sceneId, onNavigate }: { sceneId: string; onNavigate: (href: string) => void }) {
  const [scene, setScene] = useState<Scene | null>(null);
  const [subscenes, setSubscenes] = useState<SubScene[] | null>(null);
  const [rounds, setRounds] = useState<ExtractionRound[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [selectedSubsceneId, setSelectedSubsceneId] = useState<string | null>(null);

  const [sceneName, setSceneName] = useState("");
  const [sceneDescription, setSceneDescription] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveFeedback, setSaveFeedback] = useState<string | null>(null);

  const [dialogOpen, setDialogOpen] = useState(false);
  const [subsceneSaving, setSubsceneSaving] = useState(false);
  const [subsceneFormError, setSubsceneFormError] = useState<string | null>(null);
  const [subsceneFieldErrors, setSubsceneFieldErrors] = useState<Record<string, string>>({});
  const dialogTrigger = useRef<HTMLElement | null>(null);

  const [roundCreating, setRoundCreating] = useState(false);
  const [roundError, setRoundError] = useState<string | null>(null);

  const [materials, setMaterials] = useState<MaterialListItem[] | null>(null);
  const [materialsError, setMaterialsError] = useState<string | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [materialJob, setMaterialJob] = useState<MaterialJobAccepted | null>(null);
  const [materialRemovalCandidate, setMaterialRemovalCandidate] = useState<string | null>(null);
  const [materialRemoving, setMaterialRemoving] = useState<string | null>(null);
  const [materialRemovalMessage, setMaterialRemovalMessage] = useState<string | null>(null);
  const [materialRemovalError, setMaterialRemovalError] = useState<string | null>(null);
  const materialStream = useJobEvents({ jobId: materialJob?.jobId ?? null });
  const latestMaterialEvent = materialStream.events.at(-1) ?? null;
  const refreshedMaterialEvent = useRef<string | null>(null);

  const [doc, setDoc] = useState<KnowledgeDocument | null>(null);
  const [documentMissing, setDocMissing] = useState(false);
  const [docLoadError, setDocLoadError] = useState<string | null>(null);
  const [documentLoading, setDocLoading] = useState(false);
  const [editorContent, setEditorContent] = useState("");
  const [revisionNote, setRevisionNote] = useState("");
  const [revisions, setRevisions] = useState<DocumentRevisionSummary[] | null>(null);
  const [revisionsOpen, setRevisionsOpen] = useState(false);
  const [savingDoc, setSavingDoc] = useState(false);
  const [docMessage, setDocMessage] = useState<string | null>(null);
  const [docSaveError, setDocSaveError] = useState<string | null>(null);
  const [docConflict, setDocConflict] = useState(false);
  const [semanticQuery, setSemanticQuery] = useState("");
  const [semanticResults, setSemanticResults] = useState<DenseRetrievalResult[] | null>(null);
  const [semanticSearching, setSemanticSearching] = useState(false);
  const [semanticError, setSemanticError] = useState<string | null>(null);
  const [modelConfigs, setModelConfigs] = useState<AgentModelCatalogEntry[]>([]);
  const [skillVersions, setSkillVersions] = useState<AgentSkillCatalogEntry[]>([]);
  const [selectedModelConfigId, setSelectedModelConfigId] = useState("");
  const [selectedSkillVersionId, setSelectedSkillVersionId] = useState("");
  const [workflowOptionsError, setWorkflowOptionsError] = useState<string | null>(null);
  const [extractionJob, setExtractionJob] = useState<JobAccepted | null>(null);
  const [extractionStatus, setExtractionStatus] = useState<Job | null>(null);
  const [extracting, setExtracting] = useState(false);
  const [alignmentAction, setAlignmentAction] = useState<AlignmentAction>("CONSISTENCY");
  const [alignmentJob, setAlignmentJob] = useState<JobAccepted | null>(null);
  const [alignmentStatus, setAlignmentStatus] = useState<Job | null>(null);
  const [aligning, setAligning] = useState(false);
  const [proposals, setProposals] = useState<AlignmentProposal[]>([]);
  const [supportPanelTab, setSupportPanelTab] = useState<"sources" | "alignment">("sources");
  const [proposalError, setProposalError] = useState<string | null>(null);
  const [adoptingProposalId, setAdoptingProposalId] = useState<string | null>(null);

  const [assets, setAssets] = useState<Asset[] | null>(null);
  const [assetsError, setAssetsError] = useState<string | null>(null);
  const [assetJob, setAssetJob] = useState<AssetJobAccepted | null>(null);
  const [jobStatus, setJobStatus] = useState<Job | null>(null);
  const [assetAgentExecutions, setAssetAgentExecutions] = useState<AgentExecutionAttempt[]>([]);
  const [generatingTypes, setGeneratingTypes] = useState<AssetType[] | null>(null);
  const [currentUser, setCurrentUser] = useState<AuthenticatedUser | null>(null);

  const [releaseTag, setReleaseTag] = useState("");
  const [releaseNote, setReleaseNote] = useState("");
  const [releaseSelected, setReleaseSelected] = useState<string[]>([]);
  const [releaseConfirmed, setReleaseConfirmed] = useState(false);
  const [latestRelease, setLatestRelease] = useState<Release | null>(null);
  const [validatedFingerprint, setValidatedFingerprint] = useState<string | null>(null);
  const [validation, setValidation] = useState<ReleaseValidation | null>(null);
  const [releaseError, setReleaseError] = useState<string | null>(null);
  const [publishing, setPublishing] = useState(false);
  const [released, setReleased] = useState<Release | null>(null);
  const [manifest, setManifest] = useState<string | null>(null);
  const [evaluationRuns, setEvaluationRuns] = useState<EvaluationRun[]>([]);
  const [evaluationDetail, setEvaluationDetail] = useState<EvaluationDetail | null>(null);
  const [evaluationJob, setEvaluationJob] = useState<EvaluationAccepted | null>(null);
  const [evaluationJobStatus, setEvaluationJobStatus] = useState<Job | null>(null);
  const [evaluationError, setEvaluationError] = useState<string | null>(null);
  const [evaluating, setEvaluating] = useState(false);
  const [operationReadiness, setOperationReadiness] = useState<Partial<Record<ReadinessOperation, OperationReadiness>>>({});
  const jobTimer = useRef<number | null>(null);
  const workflowTimer = useRef<number | null>(null);
  const evaluationTimer = useRef<number | null>(null);
  const selectedSubsceneRef = useRef<string | null>(selectedSubsceneId);

  useEffect(() => {
    selectedSubsceneRef.current = selectedSubsceneId;
  }, [selectedSubsceneId]);

  // Switching sub-scenes stops the previous generation poll and its state.
  useEffect(() => () => {
    if (jobTimer.current !== null) {
      window.clearTimeout(jobTimer.current);
      jobTimer.current = null;
    }
    if (workflowTimer.current !== null) {
      window.clearTimeout(workflowTimer.current);
      workflowTimer.current = null;
    }
    if (evaluationTimer.current !== null) {
      window.clearTimeout(evaluationTimer.current);
      evaluationTimer.current = null;
    }
    setGeneratingTypes(null);
    setExtracting(false);
    setAligning(false);
    setEvaluating(false);
    setEvaluationRuns([]);
    setEvaluationDetail(null);
    setEvaluationJob(null);
    setEvaluationJobStatus(null);
    setEvaluationError(null);
  }, [selectedSubsceneId]);

  useEffect(() => () => {
    if (jobTimer.current !== null) window.clearTimeout(jobTimer.current);
    if (workflowTimer.current !== null) window.clearTimeout(workflowTimer.current);
    if (evaluationTimer.current !== null) window.clearTimeout(evaluationTimer.current);
  }, []);

  useEffect(() => {
    void getCurrentUser().then(setCurrentUser).catch(() => setCurrentUser(null));
  }, []);

  const loadAll = useCallback(async () => {
    setLoadError(null);
    try {
      const [loadedScene, loadedSubscenes, loadedRounds] = await Promise.all([
        getScene(sceneId),
        listSubScenes(sceneId),
        listExtractionRounds(sceneId),
      ]);
      setScene(loadedScene);
      setSceneName(loadedScene.name);
      setSceneDescription(loadedScene.description);
      setSubscenes(loadedSubscenes);
      setRounds(loadedRounds);
      setSelectedSubsceneId((current) => {
        if (current && loadedSubscenes.some((item) => item.id === current)) return current;
        return loadedSubscenes[0]?.id ?? null;
      });
    } catch (reason) {
      setScene(null);
      setSubscenes(null);
      setRounds(null);
      setLoadError(reason instanceof ApiError ? reason.message : "无法连接 API，请确认已登录后重试。");
    }
  }, [sceneId]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const activeSubscene = useMemo(
    () => subscenes?.find((item) => item.id === selectedSubsceneId) ?? null,
    [subscenes, selectedSubsceneId],
  );

  const selectedRounds = useMemo(
    () => (rounds ?? []).filter((round) => round.subSceneId === selectedSubsceneId),
    [rounds, selectedSubsceneId],
  );

  const latestRound = useMemo(
    () => selectedRounds.reduce<ExtractionRound | null>(
      (latest, round) => latest === null || round.roundNumber > latest.roundNumber ? round : latest,
      null,
    ),
    [selectedRounds],
  );

  const currentRoundId = latestRound?.id ?? null;

  useEffect(() => {
    if (!selectedSubsceneId || !currentRoundId) {
      setOperationReadiness({});
      return;
    }
    const operations: ReadinessOperation[] = step === 2
      ? ["EXTRACT", "ALIGN"]
      : step === 3 ? ["GENERATE_ASSETS", "RELEASE", "EVALUATE"] : [];
    if (operations.length === 0) return;
    let active = true;
    const context = { sceneId, subSceneId: selectedSubsceneId, roundId: currentRoundId };
    void Promise.all(operations.map(async (operation) => [operation,
      await getOperationReadiness(operation, context)] as const))
      .then((values) => {
        if (!active) return;
        setOperationReadiness((current) => ({ ...current, ...Object.fromEntries(values) }));
      })
      .catch(() => { /* Existing command validation remains the safe fallback while the API is unavailable. */ });
    return () => { active = false; };
  }, [step, sceneId, selectedSubsceneId, currentRoundId,
    materials?.map((item) => `${item.id}:${item.status}:${item.binding.active}`).join("|") ?? "",
    doc?.revisionId, doc?.finalized,
    assets?.map((item) => `${item.type}:${item.status}:${item.documentRevisionId ?? ""}`).join("|") ?? ""]);

  useEffect(() => {
    setSemanticResults(null);
    setSemanticError(null);
    setSemanticQuery("");
  }, [selectedSubsceneId, currentRoundId]);

  const runSemanticSearch = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const query = semanticQuery.trim();
    if (!selectedSubsceneId || !currentRoundId || semanticSearching || query.length < 2) return;
    setSemanticSearching(true);
    setSemanticError(null);
    try {
      setSemanticResults(await retrieveKnowledgeChunks(currentRoundId, selectedSubsceneId, query, 8));
    } catch (reason) {
      setSemanticResults(null);
      setSemanticError(reason instanceof ApiError ? reason.message : "语义检索失败，请稍后重试。");
    } finally {
      setSemanticSearching(false);
    }
  };

  const loadMaterials = useCallback(async () => {
    if (!selectedSubsceneId || !currentRoundId) {
      setMaterials(null);
      return;
    }
    setMaterialsError(null);
    try {
      setMaterials(await listWorkbenchMaterials(currentRoundId, selectedSubsceneId));
    } catch (reason) {
      setMaterials(null);
      setMaterialsError(reason instanceof ApiError ? reason.message : "无法读取素材列表。");
    }
  }, [selectedSubsceneId, currentRoundId]);

  const removeMaterialFromRound = async (material: MaterialListItem) => {
    if (materialRemoving !== null) return;
    setMaterialRemoving(material.binding.id);
    setMaterialRemovalError(null);
    setMaterialRemovalMessage(null);
    try {
      await deactivateMaterialBinding(material.id, material.binding.id);
      setMaterials((current) => current?.filter((item) => item.binding.id !== material.binding.id) ?? current);
      setMaterialRemovalCandidate(null);
      setMaterialRemovalMessage(`“${material.fileName}”已移出本轮，历史记录仍保留。`);
    } catch (reason) {
      setMaterialRemovalError(reason instanceof ApiError ? reason.message : "无法将素材移出本轮。");
    } finally {
      setMaterialRemoving(null);
    }
  };

  useEffect(() => {
    if (!latestMaterialEvent
        || (latestMaterialEvent.type !== "completed" && latestMaterialEvent.type !== "failed")
        || refreshedMaterialEvent.current === latestMaterialEvent.eventId) return;
    refreshedMaterialEvent.current = latestMaterialEvent.eventId;
    void loadMaterials();
  }, [latestMaterialEvent, loadMaterials]);

  useEffect(() => {
    void loadMaterials();
  }, [loadMaterials]);

  const loadDocument = useCallback(async () => {
    if (!selectedSubsceneId) return;
    setDocLoading(true);
    setDocLoadError(null);
    setDocConflict(false);
    setDocMessage(null);
    try {
      const loaded = await getKnowledgeDocument(selectedSubsceneId);
      setDoc(loaded);
      setDocMissing(false);
      setEditorContent(loaded.contentMd);
      setRevisionNote("");
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 404) {
        setDoc(null);
        setDocMissing(true);
        setEditorContent("");
      } else {
        setDoc(null);
        setDocMissing(false);
        setDocLoadError(reason instanceof ApiError ? reason.message : "无法读取知识文档。");
      }
    } finally {
      setDocLoading(false);
    }
  }, [selectedSubsceneId]);

  useEffect(() => {
    if (step === 2 || step === 3) void loadDocument();
  }, [step, loadDocument]);

  const loadWorkflowOptions = useCallback(async () => {
    setWorkflowOptionsError(null);
    try {
      const catalog = await getAgentConfigurationCatalog();
      const configs = [...catalog.models]
        .sort((a, b) => b.version - a.version || a.connectionName.localeCompare(b.connectionName, "zh-CN"));
      const versions = catalog.skills
        .filter((skill) => skill.kind === "TEMPLATE" || skill.sceneId === sceneId)
        .sort((a, b) => b.version - a.version || a.name.localeCompare(b.name, "zh-CN"));
      setModelConfigs(configs);
      setSkillVersions(versions);
      setSelectedModelConfigId((current) => configs.some((item) => item.versionId === current)
        ? current : configs[0]?.versionId ?? "");
      setSelectedSkillVersionId((current) => versions.some((item) => item.versionId === current)
        ? current : versions[0]?.versionId ?? "");
    } catch (reason) {
      setWorkflowOptionsError(reason instanceof ApiError ? reason.message : "无法读取模型与 Skill 版本。");
    }
  }, [sceneId]);

  const loadProposals = useCallback(async () => {
    if (!selectedSubsceneId || documentMissing) {
      setProposals([]);
      return;
    }
    setProposalError(null);
    try {
      setProposals(await listAlignmentProposals(selectedSubsceneId));
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 404) setProposals([]);
      else setProposalError(reason instanceof ApiError ? reason.message : "无法读取 AI 对齐提案。");
    }
  }, [selectedSubsceneId, documentMissing]);

  useEffect(() => {
    if (step === 2) {
      void loadWorkflowOptions();
      if (doc) void loadProposals();
    }
  }, [step, doc?.revisionId, loadWorkflowOptions, loadProposals]);

  const loadAssets = useCallback(async () => {
    if (!selectedSubsceneId) return;
    setAssetsError(null);
    try {
      setAssets(await listSubSceneAssets(selectedSubsceneId));
    } catch (reason) {
      setAssets(null);
      setAssetsError(reason instanceof ApiError ? reason.message : "无法读取资产列表。");
    }
  }, [selectedSubsceneId]);

  useEffect(() => {
    if (step === 3) void loadAssets();
  }, [step, loadAssets]);

  const loadReleaseBaseline = useCallback(async () => {
    if (!scene) return;
    try {
      setLatestRelease(await getLatestRelease(scene.id));
    } catch {
      setLatestRelease(null);
    }
  }, [scene]);

  useEffect(() => {
    if (step === 3) void loadReleaseBaseline();
  }, [step, loadReleaseBaseline]);

  const loadEvaluations = useCallback(async () => {
    if (!latestRelease || !selectedSubsceneId) {
      setEvaluationRuns([]);
      setEvaluationDetail(null);
      return;
    }
    setEvaluationError(null);
    try {
      const runs = await listEvaluationRuns(latestRelease.id, selectedSubsceneId);
      setEvaluationRuns(runs);
      setEvaluationDetail(runs[0] ? await getEvaluationRun(runs[0].id) : null);
    } catch (reason) {
      setEvaluationRuns([]);
      setEvaluationDetail(null);
      setEvaluationError(reason instanceof ApiError ? reason.message : "无法读取评测记录。");
    }
  }, [latestRelease, selectedSubsceneId]);

  useEffect(() => {
    if (step === 3) void loadEvaluations();
  }, [step, loadEvaluations]);

  const isDirty = documentMissing
    ? editorContent.trim().length > 0
    : doc !== null && editorContent !== doc.contentMd;

  const sourceAnchorCount = useMemo(() => {
    const matches = editorContent.match(/\[SRC-[A-Za-z0-9_-]{1,100}]/g);
    return matches?.length ?? 0;
  }, [editorContent]);

  const saveDocument = async (finalize: boolean) => {
    if (!selectedSubsceneId || savingDoc) return;
    setSavingDoc(true);
    setDocMessage(null);
    setDocSaveError(null);
    setDocConflict(false);
    try {
      const draft: SaveDocumentDraft = { subSceneId: selectedSubsceneId, contentMd: editorContent, finalize };
      if (revisionNote.trim()) draft.revisionNote = revisionNote.trim();
      const ifMatch = documentMissing || doc === null ? "*" : doc.etag;
      const saved = await saveKnowledgeDocument(selectedSubsceneId, draft, ifMatch);
      setDoc(saved);
      setDocMissing(false);
      setEditorContent(saved.contentMd);
      setRevisionNote("");
      setDocMessage(finalize
        ? `文档已定稿（Revision v${saved.revisionNumber}）。`
        : `已保存 Revision v${saved.revisionNumber}。`);
    } catch (reason) {
      if (reason instanceof ApiError && (reason.status === 409 || reason.status === 412 || reason.status === 428)) {
        setDocConflict(true);
        setDocSaveError(reason.message);
      } else {
        setDocSaveError(reason instanceof ApiError ? reason.message : "保存失败，请稍后重试。");
      }
    } finally {
      setSavingDoc(false);
    }
  };

  const toggleRevisions = async () => {
    if (revisionsOpen) {
      setRevisionsOpen(false);
      return;
    }
    if (!selectedSubsceneId) return;
    setRevisionsOpen(true);
    try {
      setRevisions(await listDocumentRevisions(selectedSubsceneId));
    } catch {
      setRevisions([]);
    }
  };

  const pollWorkflowJob = (jobId: string, kind: "extraction" | "alignment") => {
    const tick = async () => {
      try {
        const status = await getJob(jobId);
        if (kind === "extraction") setExtractionStatus(status);
        else setAlignmentStatus(status);
        if (["SUCCEEDED", "FAILED", "CANCELLED"].includes(status.status)) {
          if (workflowTimer.current !== null) {
            window.clearTimeout(workflowTimer.current);
            workflowTimer.current = null;
          }
          if (kind === "extraction") {
            setExtracting(false);
            if (status.status === "SUCCEEDED") {
              await loadDocument();
              setDocMessage("Map/Reduce 萃取完成，已生成新的可验证 Revision。");
            } else {
              setDocSaveError(`萃取任务未完成：${status.errorCode ?? status.status}`);
            }
          } else {
            setAligning(false);
            if (status.status === "SUCCEEDED") {
              await loadProposals();
            } else {
              setProposalError(`对齐任务未完成：${status.errorCode ?? status.status}`);
            }
          }
          return;
        }
        workflowTimer.current = window.setTimeout(() => void tick(), 1500);
      } catch (reason) {
        if (workflowTimer.current !== null) window.clearTimeout(workflowTimer.current);
        workflowTimer.current = null;
        if (kind === "extraction") {
          setExtracting(false);
          setDocSaveError(reason instanceof ApiError ? reason.message : "无法读取萃取任务状态。");
        } else {
          setAligning(false);
          setProposalError(reason instanceof ApiError ? reason.message : "无法读取对齐任务状态。");
        }
      }
    };
    void tick();
  };

  const retryExtractionJob = async () => {
    if (!extractionJob || extracting) return;
    setExtracting(true);
    setDocSaveError(null);
    try {
      const status = await getJob(extractionJob.jobId);
      setExtractionStatus(status);
      if (status.status === "SUCCEEDED") {
        setExtracting(false);
        await loadDocument();
        setDocMessage("Map/Reduce 萃取完成，已生成新的可验证 Revision。");
      } else if (status.status === "FAILED") {
        const accepted = await retryJob(extractionJob.jobId, crypto.randomUUID());
        setExtractionJob(accepted);
        setExtractionStatus(null);
        pollWorkflowJob(extractionJob.jobId, "extraction");
      } else if (status.status === "CANCELLED") {
        setExtracting(false);
        setDocSaveError(`萃取任务未完成：${status.errorCode ?? status.status}`);
      } else {
        pollWorkflowJob(extractionJob.jobId, "extraction");
      }
    } catch (reason) {
      setExtracting(false);
      setDocSaveError(reason instanceof ApiError ? reason.message : "无法读取萃取任务状态。");
    }
  };

  const runExtraction = async () => {
    if (!selectedSubsceneId || !currentRoundId || !selectedModelConfigId || !selectedSkillVersionId || extracting) return;
    setExtracting(true);
    setExtractionJob(null);
    setExtractionStatus(null);
    setDocSaveError(null);
    try {
      const accepted = await startExtraction(selectedSubsceneId, currentRoundId, selectedModelConfigId,
        selectedSkillVersionId, crypto.randomUUID());
      setExtractionJob(accepted);
      pollWorkflowJob(accepted.jobId, "extraction");
    } catch (reason) {
      setExtracting(false);
      setDocSaveError(reason instanceof ApiError ? reason.message : "发起知识萃取失败。");
    }
  };

  const runAlignment = async () => {
    if (!selectedSubsceneId || !doc || isDirty || aligning) return;
    const regulatoryMaterialIds = alignmentAction === "REGULATORY"
      ? (materials ?? []).filter((material) => material.binding.regulatorySource && material.status === "READY")
        .map((material) => material.id)
      : [];
    if (alignmentAction === "REGULATORY" && regulatoryMaterialIds.length === 0) {
      setProposalError("监管对齐需要当前轮次至少一份 READY 且标记为监管依据的素材。");
      return;
    }
    setAligning(true);
    setAlignmentJob(null);
    setAlignmentStatus(null);
    setProposalError(null);
    try {
      const accepted = await startAlignment(selectedSubsceneId, doc.revisionId, alignmentAction,
        regulatoryMaterialIds, crypto.randomUUID());
      setAlignmentJob(accepted);
      pollWorkflowJob(accepted.jobId, "alignment");
    } catch (reason) {
      setAligning(false);
      setProposalError(reason instanceof ApiError ? reason.message : "发起 AI 对齐失败。");
    }
  };

  const adoptProposal = async (proposal: AlignmentProposal) => {
    if (isDirty || adoptingProposalId !== null) return;
    setAdoptingProposalId(proposal.id);
    setProposalError(null);
    try {
      const saved = await adoptAlignmentProposal(proposal.id, proposal.baseEtag);
      setDoc(saved);
      setEditorContent(saved.contentMd);
      setDocMissing(false);
      setDocMessage(`已采纳 Proposal，生成 Revision v${saved.revisionNumber}。`);
      await loadProposals();
    } catch (reason) {
      setProposalError(reason instanceof ApiError ? reason.message : "采纳 Proposal 失败。");
    } finally {
      setAdoptingProposalId(null);
    }
  };

  const canGenerate = doc !== null && !documentMissing && doc.finalized;

  const pollJob = (jobId: string, pollingSubScene: string) => {
    const tick = async () => {
      try {
        const job = await getJob(jobId);
        setJobStatus(job);
        if (job.status === "SUCCEEDED" || job.status === "FAILED" || job.status === "CANCELLED") {
          if (jobTimer.current !== null) {
            window.clearTimeout(jobTimer.current);
            jobTimer.current = null;
          }
          setGeneratingTypes(null);
          try {
            setAssetAgentExecutions(await listJobAgentExecutions(jobId));
          } catch {
            setAssetAgentExecutions([]);
          }
          // Never let a stale job refresh the currently viewed sub-scene's assets.
          if (pollingSubScene === selectedSubsceneRef.current) {
            await loadAssets();
          }
          return;
        }
        jobTimer.current = window.setTimeout(() => void tick(), 2000);
      } catch {
        if (jobTimer.current !== null) {
          window.clearTimeout(jobTimer.current);
          jobTimer.current = null;
        }
        setGeneratingTypes(null);
        setAssetsError("无法读取资产生成任务状态。");
      }
    };
    void tick();
  };

  const startGeneration = async (types: AssetType[]) => {
    if (!canGenerate || !doc || !selectedSubsceneId || generatingTypes !== null) return;
    setGeneratingTypes(types);
    setAssetJob(null);
    setJobStatus(null);
    setAssetAgentExecutions([]);
    setAssetsError(null);
    try {
      const accepted = await generateAssets(selectedSubsceneId, doc.revisionId, types, crypto.randomUUID());
      setAssetJob(accepted);
      pollJob(accepted.jobId, selectedSubsceneId);
    } catch (reason) {
      setGeneratingTypes(null);
      setAssetsError(reason instanceof ApiError ? reason.message : "发起资产生成失败。");
    }
  };

  const canPublish = Boolean(
    currentUser && (currentUser.roles.includes("PUBLISHER") || currentUser.roles.includes("ADMIN")),
  );
  const canEvaluate = Boolean(
    currentUser && (currentUser.roles.includes("OPERATOR") || currentUser.roles.includes("ADMIN")),
  );
  const readyHoldoutCount = (materials ?? []).filter((material) =>
    material.status === "READY" && material.binding.partition === "LABELED_HOLDOUT" && material.binding.active).length;
  const activeEvaluation = evaluationDetail?.run ?? evaluationRuns[0] ?? null;

  const releaseFingerprint = () =>
    `${releaseTag.trim()}|${releaseNote.trim()}|${[...releaseSelected].sort().join(",")}|${releaseConfirmed}`;

  const runPreflight = async () => {
    if (!scene) return;
    setReleaseError(null);
    setValidation(null);
    if (!releaseTag.trim() || releaseSelected.length === 0 || !releaseConfirmed) {
      setReleaseError("请填写发布 tag、选择子场景，并完成二次确认。");
      return;
    }
    if (!/^v[0-9]+\.[0-9]+(?:\.[0-9]+)?$/.test(releaseTag.trim())) {
      setReleaseError("发布 tag 必须是 vX.Y 或 vX.Y.Z 语义化版本。");
      return;
    }
    try {
      const validated = await validateRelease(scene.id, {
        tag: releaseTag.trim(),
        selectedSubSceneIds: releaseSelected,
        note: releaseNote.trim(),
        confirmed: true,
        expectedBaseReleaseId: latestRelease?.id ?? null,
      });
      setValidation(validated);
      setValidatedFingerprint(releaseFingerprint());
    } catch (reason) {
      setReleaseError(reason instanceof ApiError ? reason.message : "发布预检失败。");
    }
  };

  const draftStale = validatedFingerprint !== null && releaseFingerprint() !== validatedFingerprint;

  const publishRelease = async () => {
    if (!scene || !validation?.ready || publishing) return;
    if (draftStale) {
      setReleaseError("发布内容已变更，请重新预检后再发布。");
      setValidation(null);
      setValidatedFingerprint(null);
      return;
    }
    setPublishing(true);
    setReleaseError(null);
    try {
      const created = await createRelease(scene.id, {
        tag: releaseTag.trim(),
        selectedSubSceneIds: releaseSelected,
        note: releaseNote.trim(),
        confirmed: true,
        expectedBaseReleaseId: latestRelease?.id ?? null,
      }, crypto.randomUUID());
      setReleased(created);
      setValidation(null);
      setValidatedFingerprint(null);
      setLatestRelease(created);
      setEvaluationRuns([]);
      setEvaluationDetail(null);
    } catch (reason) {
      setReleaseError(reason instanceof ApiError ? reason.message : "发布失败。");
    } finally {
      setPublishing(false);
    }
  };

  const pollEvaluationJob = (accepted: EvaluationAccepted, pollingSubScene: string) => {
    const tick = async () => {
      try {
        const status = await getJob(accepted.jobId);
        if (selectedSubsceneRef.current !== pollingSubScene) return;
        setEvaluationJobStatus(status);
        if (["SUCCEEDED", "FAILED", "CANCELLED"].includes(status.status)) {
          if (evaluationTimer.current !== null) {
            window.clearTimeout(evaluationTimer.current);
            evaluationTimer.current = null;
          }
          setEvaluating(false);
          try {
            setEvaluationDetail(await getEvaluationRun(accepted.evaluationRunId));
            if (latestRelease && selectedSubsceneRef.current === pollingSubScene) {
              setEvaluationRuns(await listEvaluationRuns(latestRelease.id, pollingSubScene));
            }
          } catch (reason) {
            setEvaluationError(reason instanceof ApiError ? reason.message : "无法读取评测证据。");
          }
          if (status.status !== "SUCCEEDED") {
            setEvaluationError(`评测任务未完成：${status.errorCode ?? status.status}`);
          }
          return;
        }
        evaluationTimer.current = window.setTimeout(() => void tick(), 1500);
      } catch (reason) {
        setEvaluating(false);
        setEvaluationError(reason instanceof ApiError ? reason.message : "无法读取评测任务状态。");
      }
    };
    void tick();
  };

  const runEvaluation = async () => {
    if (!latestRelease || !selectedSubsceneId || !currentRoundId || evaluating) return;
    const evaluationSubSceneId = selectedSubsceneId;
    setEvaluating(true);
    setEvaluationError(null);
    setEvaluationJob(null);
    setEvaluationJobStatus(null);
    try {
      const accepted = await startReleaseEvaluation(latestRelease.id, selectedSubsceneId,
        currentRoundId, crypto.randomUUID());
      if (selectedSubsceneRef.current !== evaluationSubSceneId) return;
      setEvaluationJob(accepted);
      pollEvaluationJob(accepted, evaluationSubSceneId);
    } catch (reason) {
      setEvaluating(false);
      setEvaluationError(reason instanceof ApiError ? reason.message : "发起留出集评测失败。");
    }
  };

  const openManifest = async () => {
    if (!released) return;
    setManifest(null);
    try {
      setManifest(await getReleaseManifest(released.id));
    } catch {
      setManifest("无法读取发布清单。");
    }
  };

  const downloadManifest = async () => {
    if (!released) return;
    try {
      const text = await getReleaseManifest(released.id);
      const blob = new Blob([text], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `manifest-${released.tag}.json`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch {
      setReleaseError("无法下载发布清单。");
    }
  };

  const sceneChanged = scene !== null && (sceneName !== scene.name || sceneDescription !== scene.description);

  const saveScene = async () => {
    if (!scene || !sceneChanged || saving) return;
    setSaving(true);
    setSaveFeedback(null);
    try {
      const updated = await updateScene(scene.id, { name: sceneName, description: sceneDescription || undefined });
      setScene(updated);
      setSceneName(updated.name);
      setSceneDescription(updated.description);
      setSaveFeedback("场景已保存。");
    } catch (reason) {
      setSaveFeedback(reason instanceof ApiError ? reason.message : "保存失败，请稍后重试。");
    } finally {
      setSaving(false);
    }
  };

  const openDialog = () => {
    dialogTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSubsceneFormError(null);
    setSubsceneFieldErrors({});
    setDialogOpen(true);
  };

  const closeDialog = () => {
    setDialogOpen(false);
    dialogTrigger.current?.focus();
  };

  const submitSubScene = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (subsceneSaving) return;
    const form = event.currentTarget;
    const formData = new FormData(form);
    const name = String(formData.get("name") ?? "").trim();
    const description = String(formData.get("description") ?? "").trim();
    if (!name) {
      setSubsceneFormError("请输入子场景名称。");
      return;
    }
    setSubsceneSaving(true);
    try {
      const created = await createSubScene(sceneId, { name, description: description || undefined });
      closeDialog();
      await loadAll();
      setSelectedSubsceneId(created.id);
    } catch (reason) {
      if (reason instanceof ApiError) {
        setSubsceneFormError(reason.message);
        setSubsceneFieldErrors(fieldErrorsToRecord(reason.errors));
      } else {
        setSubsceneFormError("创建失败，请稍后重试。");
      }
    } finally {
      setSubsceneSaving(false);
    }
  };

  const startNewRound = async () => {
    if (!selectedSubsceneId || roundCreating) return;
    setRoundCreating(true);
    setRoundError(null);
    try {
      await createExtractionRound(sceneId, selectedSubsceneId);
      await loadAll();
    } catch (reason) {
      setRoundError(reason instanceof ApiError ? reason.message : "创建轮次失败，请稍后重试。");
    } finally {
      setRoundCreating(false);
    }
  };

  const currentLineage = steps.find((item) => item.id === step)?.stage as LineageStage;

  if (loadError) {
    return (
      <div className="page">
        <div className="load-error" role="alert">
          <Glyph name="warning" size={16} />
          <div><b>无法加载场景</b><span>{loadError}</span></div>
          <Button className="button--quiet button--small" onClick={() => void loadAll()}>重试</Button>
          <Button className="button--quiet button--small" onClick={() => onNavigate("/")}>返回场景库</Button>
        </div>
      </div>
    );
  }

  if (scene === null || subscenes === null || rounds === null) {
    return <div className="page"><div className="model-loading" aria-busy="true">正在加载场景…</div></div>;
  }

  const hasSubscenes = subscenes.length > 0;

  return (
    <div className="scene-workspace">
      <div className="scene-context">
        <button className="back-link" onClick={() => onNavigate("/")}><span aria-hidden="true">←</span> 场景库</button>
        <div className="scene-context__title">
          <div><span className="eyebrow">SCENE / {scene.id}</span><h1>{scene.name}</h1></div>
          <span className="round-chip">更新于 {scene.updatedAt.slice(0, 10)}</span>
        </div>
        <div className="rounds" aria-label="萃取轮次">
          <span><Glyph name="history" size={15} /> 萃取轮次</span>
          {hasSubscenes ? selectedRounds.map((round) => (
            <button key={round.id} className={latestRound?.id === round.id ? "active" : ""}
              aria-current={latestRound?.id === round.id ? "true" : undefined}>
              v{round.roundNumber} · {ROUND_STATUS_LABELS[round.status]}
            </button>
          )) : <span className="rounds-hint">需要至少一个子场景才能创建轮次</span>}
          <button onClick={() => void startNewRound()} disabled={!hasSubscenes || roundCreating}
            title={hasSubscenes ? undefined : "请先添加子场景"}>
            {roundCreating ? <><Glyph name="plus" size={13} /> 创建中…</> : <><Glyph name="plus" size={13} /> 新一轮</>}
          </button>
        </div>
        {roundError ? <div className="form-error" role="alert">{roundError}</div> : null}
      </div>

      <div className="scene-grid-layout">
        <aside className="workflow-nav">
          <ol>
            {steps.map((item) => (
              <li key={item.id} className={step === item.id ? "active" : step > item.id ? "done" : ""}>
                <button onClick={() => setStep(item.id)}>
                  <span className="workflow-nav__node">{step > item.id ? <Glyph name="check" size={14} /> : item.id}</span>
                  <span><b>{item.title}</b><small>{item.detail}</small></span>
                </button>
              </li>
            ))}
          </ol>
          <div className="workflow-nav__meta">
            <span>当前子场景</span>
            <strong>{activeSubscene?.name ?? "未选择"}</strong>
            <small>{latestRound ? `v${latestRound.roundNumber} · ${ROUND_STATUS_LABELS[latestRound.status]}` : "尚未创建轮次"}</small>
          </div>
        </aside>

        <div className="workflow-main">
          <LineageRail active={currentLineage} compact />

          {step === 1 ? (
            <section className="workflow-step" aria-labelledby="step-materials-title">
              <header className="step-heading">
                <div><span>STEP 01</span><h2 id="step-materials-title">定义边界，固定本轮素材</h2><p>子场景决定萃取范围；素材按当前子场景与最新轮次绑定。</p></div>
                <Button className="button--primary" onClick={() => setStep(2)}>进入知识萃取 <Glyph name="chevron" size={15} /></Button>
              </header>
              <div className="materials-layout">
                <section className="panel">
                  <div className="panel__head">
                    <div><span className="panel__index">A</span><h3>场景与子场景</h3></div>
                    <Button className="button--quiet button--small" onClick={openDialog}><Glyph name="plus" size={14} /> 添加子场景</Button>
                  </div>
                  <div className="field-grid">
                    <label className="field">
                      <span>场景名称</span>
                      <input value={sceneName} maxLength={200}
                        onChange={(event) => setSceneName(event.currentTarget.value)} />
                    </label>
                    <label className="field field--full">
                      <span>场景描述</span>
                      <textarea value={sceneDescription} maxLength={10000}
                        onChange={(event) => setSceneDescription(event.currentTarget.value)} />
                    </label>
                    <div className="field field--full scene-save-row">
                      <Button className="button--primary button--small" disabled={!sceneChanged || saving}
                        onClick={() => void saveScene()}>
                        {saving ? "保存中…" : "保存场景"}
                      </Button>
                      {saveFeedback ? <span className="scene-save-feedback" role="status">{saveFeedback}</span> : null}
                    </div>
                  </div>
                  <div className="subscene-stack">
                    {subscenes.map((subscene, index) => (
                      <button key={subscene.id}
                        className={`subscene-item ${selectedSubsceneId === subscene.id ? "active" : ""}`}
                        onClick={() => setSelectedSubsceneId(subscene.id)}>
                        <span className="subscene-item__index">{String(index + 1).padStart(2, "0")}</span>
                        <span><b>{subscene.name}</b><small>{subscene.description || "暂无描述"}</small></span>
                        <Status tone={toStatusTone("READY")}>子场景</Status>
                      </button>
                    ))}
                    {!hasSubscenes ? <div className="subscene-empty">还没有子场景；点击“添加子场景”创建第一个。</div> : null}
                  </div>
                </section>

                <section className="panel">
                  <div className="panel__head">
                    <div><span className="panel__index">B</span><h3>本轮素材</h3></div>
                    <div className="panel__actions">
                      <Button className="button--quiet button--small" onClick={() => void loadMaterials()}
                        disabled={!hasSubscenes || !latestRound}><Glyph name="history" size={14} /> 刷新</Button>
                      <Button className="button--quiet button--small" onClick={() => setUploadOpen(true)}
                        disabled={!hasSubscenes || !latestRound}><Glyph name="plus" size={14} /> 上传素材</Button>
                    </div>
                  </div>
                  {materialJob ? (
                    <div className={`material-event-stream ${latestMaterialEvent?.type === "failed" ? "is-failed" : ""}`}
                      role="status" aria-live="polite">
                      <div className="material-event-stream__head">
                        <div>
                          <b>{latestMaterialEvent ? materialEventLabel(latestMaterialEvent) : "校验任务已排队"}</b>
                          <small>任务 {materialJob.jobId} · SSE {materialStream.connection === "open" ? "已连接" : materialStream.connection === "closed" ? "已完成" : "连接中"}</small>
                        </div>
                        {(latestMaterialEvent?.type === "completed" || latestMaterialEvent?.type === "failed") ? (
                          <button type="button" className="button button--quiet button--small"
                            onClick={() => setMaterialJob(null)}>关闭</button>
                        ) : null}
                      </div>
                      <div className="material-event-stream__progress">
                        <progress max={100} value={latestMaterialEvent?.percent ?? 0}
                          aria-label="素材处理进度" />
                        <span>{latestMaterialEvent?.percent ?? 0}%</span>
                      </div>
                      {materialStream.events.length > 0 ? (
                        <ol>
                          {materialStream.events.slice(-3).map((event) => (
                            <li key={event.eventId} className={event.type === "failed" ? "is-failed" : ""}>
                              <time>{new Date(event.createdAt).toLocaleTimeString("zh-CN", { hour12: false })}</time>
                              <span>{materialEventLabel(event)}</span>
                            </li>
                          ))}
                        </ol>
                      ) : null}
                    </div>
                  ) : null}
                  {materialRemovalMessage ? (
                    <div className="material-action-note" role="status">
                      <span>{materialRemovalMessage}</span>
                      <button type="button" aria-label="关闭素材操作提示"
                        onClick={() => setMaterialRemovalMessage(null)}>关闭</button>
                    </div>
                  ) : null}
                  {materialRemovalError ? (
                    <div className="materials-error" role="alert">
                      <span>{materialRemovalError}</span>
                      <Button className="button--quiet button--small"
                        onClick={() => setMaterialRemovalError(null)}>关闭</Button>
                    </div>
                  ) : null}
                  {!hasSubscenes ? (
                    <div className="subscene-empty">请先在面板 A 添加子场景。</div>
                  ) : !latestRound ? (
                    <div className="subscene-empty">请先为当前子场景创建“新一轮”。</div>
                  ) : materialsError ? (
                    <div className="materials-error" role="alert">
                      <span>{materialsError}</span>
                      <Button className="button--quiet button--small" onClick={() => void loadMaterials()}>重试</Button>
                    </div>
                  ) : materials === null ? (
                    <div className="model-loading" aria-busy="true">正在加载素材列表…</div>
                  ) : materials.length === 0 ? (
                    <EmptyState title="还没有素材" detail="点击“上传素材”上传第一份文件，浏览器本地计算 SHA-256 后分片直传隔离区。" />
                  ) : (
                    <div className="material-list">
                      {materials.map((material) => (
                        <article className="material-row" key={material.id}>
                          <span className={`file-type file-type--${material.format.toLowerCase()}`}>{material.format}</span>
                          <div className="material-row__main">
                            <b>{material.fileName}</b>
                            <small>{formatBytes(material.sizeBytes)} · {partitionLabels[material.binding.partition]}
                              {material.binding.regulatorySource ? " · 监管依据" : ""}</small>
                          </div>
                          <Status tone={toStatusTone(material.status)}>
                            {MATERIAL_STATUS_LABELS[material.status] ?? material.status}
                          </Status>
                          <div className="material-row__actions">
                            {materialRemovalCandidate === material.binding.id ? (
                              <>
                                <button type="button" className="material-row__action material-row__action--confirm"
                                  aria-label={`确认移出本轮 ${material.fileName}`}
                                  disabled={materialRemoving !== null}
                                  onClick={() => void removeMaterialFromRound(material)}>
                                  {materialRemoving === material.binding.id ? "移出中" : "确认"}
                                </button>
                                <button type="button" className="material-row__action"
                                  aria-label={`取消移出本轮 ${material.fileName}`}
                                  disabled={materialRemoving !== null}
                                  onClick={() => setMaterialRemovalCandidate(null)}>取消</button>
                              </>
                            ) : (
                              <button type="button" className="material-row__action"
                                aria-label={`移出本轮 ${material.fileName}`}
                                title="移出本轮；文件与审计记录仍会保留"
                                disabled={!canEvaluate || materialRemoving !== null}
                                onClick={() => setMaterialRemovalCandidate(material.binding.id)}>移出</button>
                            )}
                          </div>
                        </article>
                      ))}
                    </div>
                  )}
                </section>
              </div>
            </section>
          ) : null}

          {step === 2 ? (
            <section className="workflow-step" aria-labelledby="step-extraction-title">
              <header className="step-heading">
                <div><span>STEP 02</span><h2 id="step-extraction-title">萃取、核对并固定 Revision</h2><p>{activeSubscene?.name ?? "未选择子场景"} · Map/Reduce 产出 KnowledgeIR；人工保存与 Proposal 采纳均由 ETag 保护。</p></div>
              </header>
              <section className="workflow-runner" aria-label="萃取与对齐任务">
                <div className="workflow-runner__head">
                  <div>
                    <b>萃取运行配置</b>
                    <span>选定的模型与 Skill 版本会固化到本次任务，保证结果可追溯。</span>
                  </div>
                  <Status tone={modelConfigs.length > 0 && skillVersions.length > 0 ? "success" : "warning"}>
                    {modelConfigs.length} 个模型 · {skillVersions.length} 个 Skill
                  </Status>
                </div>
                <OperationReadinessNotice report={operationReadiness.EXTRACT ?? null}
                  label="知识萃取" onNavigate={onNavigate}/>
                <div className="workflow-runner__config">
                  <label className="field workflow-config-field">
                    <span><b>模型配置版本</b><small>用于本轮 Map/Reduce 推理</small></span>
                    <select aria-label="模型配置版本" value={selectedModelConfigId}
                      onChange={(event) => setSelectedModelConfigId(event.currentTarget.value)}>
                      <option value="">请选择</option>
                      {modelConfigs.map((config) => (
                        <option key={config.versionId} value={config.versionId}>
                          {config.connectionName} / {config.modelId} · v{config.version}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="field workflow-config-field">
                    <span><b>Skill 版本</b><small>定义萃取边界、结构与来源约束</small></span>
                    <select aria-label="Skill 版本" value={selectedSkillVersionId}
                      onChange={(event) => setSelectedSkillVersionId(event.currentTarget.value)}>
                      <option value="">请选择</option>
                      {skillVersions.map((version) => (
                        <option key={version.versionId} value={version.versionId}>
                          {version.name} · v{version.version} · {version.kind === "TEMPLATE" ? "通用模板" : "场景实例"}
                        </option>
                      ))}
                    </select>
                  </label>
                  <div className="workflow-runner__action">
                    <Button className="button--primary button--small" onClick={() => void runExtraction()}
                      disabled={extracting || !currentRoundId || !selectedModelConfigId || !selectedSkillVersionId
                        || operationReadiness.EXTRACT?.ready === false}>
                      {extracting ? "萃取中…" : "开始 Map/Reduce 萃取"}
                    </Button>
                    <small>{currentRoundId ? "任务将在 Worker 中异步执行" : "请先创建萃取轮次"}</small>
                  </div>
                </div>
                {modelConfigs.length === 0 ? (
                  <div className="workflow-option-empty" role="status">
                    <Glyph name="model" size={17} />
                    <div><b>还没有可用的模型配置版本</b><span>先创建模型连接并保存一个模型配置版本。</span></div>
                    <Button className="button--text button--small" onClick={() => onNavigate("/models")}>前往模型配置</Button>
                  </div>
                ) : null}
                {skillVersions.length === 0 ? (
                  <div className="workflow-option-empty" role="status">
                    <Glyph name="skill" size={17} />
                    <div><b>还没有可用的 Skill 版本</b><span>系统会提供基础模板，也可以在 Skill 库中创建场景实例。</span></div>
                    <Button className="button--text button--small" onClick={() => onNavigate("/skills")}>前往 Skill 库</Button>
                  </div>
                ) : null}
                {workflowOptionsError ? <div className="form-error" role="alert">{workflowOptionsError}</div> : null}
                {extractionJob ? (
                  <div className="job-strip" aria-live="polite">
                    <div className={`job-strip__pulse job-strip__pulse--${extractionStatus?.status === "SUCCEEDED" ? "closed" : extractionStatus?.status === "FAILED" ? "failed" : "open"}`} />
                    <div>
                      <b>萃取任务 {extractionJob.jobId}</b>
                      <small>{extractionStatus
                        ? `${extractionStatus.status}${extractionStatus.errorCode ? ` · ${extractionStatus.errorCode}` : ""}${extractionStatus.stage ? ` · ${extractionStatus.stage}` : ""}`
                        : "QUEUED"} · {extractionStatus?.percent ?? 0}%</small>
                    </div>
                    <progress className="job-strip__progress" max={100} value={extractionStatus?.percent ?? 0} aria-label="萃取任务进度" />
                    {extractionStatus && ["FAILED", "CANCELLED"].includes(extractionStatus.status) ? (
                      <Button className="button--quiet button--small" disabled={extracting}
                        onClick={() => void retryExtractionJob()}>{extracting ? "检查中…" : extractionStatus.status === "FAILED" ? "重试任务" : "刷新状态"}</Button>
                    ) : <strong>{extractionStatus?.percent ?? 0}%</strong>}
                  </div>
                ) : null}
              </section>
               {docLoadError ? (
                <div className="load-error" role="alert">
                  <Glyph name="warning" size={16} />
                  <div><b>无法加载知识文档</b><span>{docLoadError}</span></div>
                  <Button className="button--quiet button--small" onClick={() => void loadDocument()}>重试</Button>
                </div>
              ) : documentLoading ? (
                <div className="model-loading" aria-busy="true">正在加载知识文档…</div>
              ) : (
                <div className="editor-layout">
                  <section className="panel doc-panel">
                    <div className="doc-toolbar">
                      <div>
                        <Status tone={doc?.finalized ? "success" : isDirty ? "warning" : "neutral"}>
                          {documentMissing ? "新文档" : doc?.finalized ? "已定稿" : isDirty ? "未保存" : "已保存"}
                        </Status>
                        <code>{doc ? `v${doc.revisionNumber}` : "v0 · 尚未创建"}</code>
                        {doc?.etag ? <span>ETag: {doc.etag.slice(0, 16)}…</span> : null}
                      </div>
                      <div>
                        <Button className="button--text" onClick={() => void toggleRevisions()}><Glyph name="history" size={14} /> Revision 历史</Button>
                        <Button className="button--quiet button--small" disabled={!isDirty || savingDoc}
                          onClick={() => void saveDocument(false)}>{savingDoc ? "保存中…" : "保存 Revision"}</Button>
                        <Button className="button--primary button--small" disabled={savingDoc || Boolean(doc?.finalized)}
                          onClick={() => void saveDocument(true)}>{savingDoc ? "保存中…" : "定稿"}</Button>
                      </div>
                    </div>
                    <label className="field editor-note-field">
                      <span>修订说明（可选）</span>
                      <input value={revisionNote} maxLength={500} placeholder="记录本次改动要点，最长 500 字符"
                        onChange={(event) => setRevisionNote(event.currentTarget.value)} />
                    </label>
                    {docConflict ? (
                      <div className="dialog-warning" role="alert">
                        <Glyph name="warning" size={15} />
                        <span>{docSaveError}；重新加载会丢失本地未保存的修改。</span>
                        <Button className="button--quiet button--small" onClick={() => void loadDocument()}>重新加载</Button>
                      </div>
                    ) : null}
                    {docSaveError && !docConflict ? <div className="form-error" role="alert">{docSaveError}</div> : null}
                    {docMessage ? <div className="dialog-success" role="status"><Glyph name="check" size={14} />{docMessage}</div> : null}
                    <label className="markdown-editor">
                      <span className="sr-only">知识文档 Markdown</span>
                      <textarea spellCheck={false} value={editorContent} aria-label="知识文档 Markdown"
                        onChange={(event) => setEditorContent(event.currentTarget.value)} />
                    </label>
                    <div className="editor-status">
                      <span>Markdown · UTF-8</span>
                      <span>{editorContent.split("\n").length} 行</span>
                      <span>{sourceAnchorCount} 个 [SRC-*] 锚点</span>
                      <span>保存时校验 kmp-* AST、KnowledgeIR Schema 与持久化来源</span>
                    </div>
                  </section>

                  <aside className="source-panel evidence-panel">
                    <div className="source-panel__head">
                      <span><Glyph name="link" size={15} />证据与对齐</span>
                      <b>{(doc?.sourceRefs.length ?? 0) + proposals.length}</b>
                    </div>
                    <div className="evidence-panel__tabs" role="tablist" aria-label="证据与对齐工具">
                      <button type="button" role="tab" aria-selected={supportPanelTab === "sources"}
                        className={supportPanelTab === "sources" ? "active" : ""}
                        onClick={() => setSupportPanelTab("sources")}>
                        来源证据 <span>{doc?.sourceRefs.length ?? 0}</span>
                      </button>
                      <button type="button" role="tab" aria-selected={supportPanelTab === "alignment"}
                        className={supportPanelTab === "alignment" ? "active" : ""}
                        onClick={() => setSupportPanelTab("alignment")}>
                        AI 对齐 <span>{proposals.length}</span>
                      </button>
                    </div>
                    <div className="evidence-panel__body">
                      {supportPanelTab === "sources" ? (
                        <div role="tabpanel" aria-label="来源证据">
                          <div className="semantic-search">
                            <form onSubmit={(event) => void runSemanticSearch(event)}>
                              <label className="field">
                                <span>中文语义检索</span>
                                <input value={semanticQuery} maxLength={1000} placeholder="检索当前轮次可信素材"
                                  onChange={(event) => setSemanticQuery(event.currentTarget.value)} />
                              </label>
                              <Button type="submit" className="button--quiet button--small"
                                disabled={semanticSearching || semanticQuery.trim().length < 2 || !currentRoundId}>
                                {semanticSearching ? "检索中…" : "检索 Chunk"}
                              </Button>
                            </form>
                            <small>SOURCE / LABELED_TRAIN · Holdout 物理隔离</small>
                            {semanticError ? <div className="form-error" role="alert">{semanticError}</div> : null}
                            {semanticResults?.length === 0 ? <p>当前范围没有可用向量结果。</p> : null}
                            {semanticResults && semanticResults.length > 0 ? (
                              <div className="semantic-results" aria-live="polite">
                                {semanticResults.map((result) => (
                                  <article key={result.chunkId}>
                                    <header><code>[{result.sourceRefCode}]</code><b>{(result.score * 100).toFixed(1)}</b></header>
                                    <span>{formatRetrievalLocator(result)}</span>
                                    <p>{result.excerpt}</p>
                                  </article>
                                ))}
                              </div>
                            ) : null}
                          </div>
                          {!doc || doc.sourceRefs.length === 0 ? (
                            <div className="subscene-empty">当前 Revision 尚无已验证来源；请先运行萃取。</div>
                          ) : (
                            doc.sourceRefs.map((ref) => (
                              <div className="source-card" key={ref.code}>
                                <code>[{ref.code}]</code><h3>{formatSourceLocator(ref)}</h3>
                                <span>素材 {ref.materialId.slice(0, 8)} · Chunk {ref.chunkId.slice(0, 8)} · {ref.excerptHash.slice(0, 12)}…</span>
                              </div>
                            ))
                          )}
                        </div>
                      ) : (
                        <div role="tabpanel" aria-label="AI 对齐">
                          <div className="proposal-card proposal-card--composer">
                            <span>生成结构化 Proposal</span>
                            <p>只生成可审计的修改建议，不会直接覆盖当前 Revision。</p>
                            <OperationReadinessNotice report={operationReadiness.ALIGN ?? null}
                              label="AI 对齐" onNavigate={onNavigate}/>
                            <label className="field">
                              <span>对齐动作</span>
                              <select value={alignmentAction} onChange={(event) => setAlignmentAction(event.currentTarget.value as AlignmentAction)}>
                                <option value="CONSISTENCY">一致性检查</option>
                                <option value="REGULATORY">监管对齐</option>
                                <option value="GAP_ANALYSIS">缺失分析</option>
                                <option value="REWRITE">结构改写</option>
                              </select>
                            </label>
                            <Button className="button--quiet button--small" disabled={!doc || isDirty || aligning
                              || operationReadiness.ALIGN?.ready === false}
                              onClick={() => void runAlignment()}>{aligning ? "生成中…" : "生成 Proposal"}</Button>
                            {alignmentJob ? <small>{alignmentStatus ? `${alignmentStatus.stage ?? alignmentStatus.status} · ${alignmentStatus.percent}%` : "已排队"}</small> : null}
                            {proposalError ? <div className="form-error" role="alert">{proposalError}</div> : null}
                          </div>
                          {proposals.length === 0 ? (
                            <div className="subscene-empty">暂无 Proposal。文档保存后可发起一致性、监管或缺失分析。</div>
                          ) : null}
                          {proposals.map((proposal) => {
                            const diff = proposal.structuredPatch.diff;
                            return (
                              <div className="proposal-card proposal-card--ready" key={proposal.id}>
                                <span>{proposal.action} · {proposal.status}</span>
                                <b>规则 +{diff.addedRuleIds.length} / -{diff.removedRuleIds.length} / 改 {diff.changedRuleIds.length}</b>
                                <p>{proposal.reason}</p>
                                <small>流程 +{diff.addedFlowIds.length} / -{diff.removedFlowIds.length} / 改 {diff.changedFlowIds.length} · 来源 Δ {diff.sourceRefDelta}</small>
                                {proposal.status === "READY" ? (
                                  <Button className="button--primary button--small" disabled={isDirty || adoptingProposalId !== null}
                                    onClick={() => void adoptProposal(proposal)}>
                                    {adoptingProposalId === proposal.id ? "采纳中…" : "按基线 ETag 采纳"}
                                  </Button>
                                ) : null}
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  </aside>

                  {revisionsOpen ? (
                    <section className="revision-history" aria-label="Revision 历史">
                      <div className="revision-history__head"><b>不可变 Revision 历史</b><span>{revisions?.length ?? 0} 个版本</span></div>
                      {revisions === null ? <div className="model-loading" aria-busy="true">正在加载…</div> : revisions.length === 0 ? (
                        <div className="subscene-empty">还没有 Revision；保存文档后生成第一个版本。</div>
                      ) : (
                        <div className="version-list">
                          {revisions.map((revision) => (
                            <div className="version-row" key={revision.id}>
                              <code className="version-chip">v{revision.revisionNumber}</code>
                              <code>{revision.contentHash.slice(0, 12)}…</code>
                              <span>{revision.finalized ? "已定稿" : "草稿"}</span>
                              <span>{revision.note ?? "无说明"}</span>
                              <time>{new Date(revision.createdAt).toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}</time>
                            </div>
                          ))}
                        </div>
                      )}
                    </section>
                  ) : null}
                </div>
              )}
            </section>
          ) : null}

          {step === 3 ? (
            <section className="workflow-step" aria-labelledby="step-assets-title">
              <header className="step-heading">
                <div><span>STEP 03</span><h2 id="step-assets-title">生成资产，检查发布覆盖</h2><p>{activeSubscene?.name ?? "未选择子场景"} · 资产由定稿文档确定性生成，发布形成不可变 Manifest。</p></div>
              </header>
              {assetsError ? (
                <div className="load-error" role="alert">
                  <Glyph name="warning" size={16} />
                  <div><b>无法读取资产</b><span>{assetsError}</span></div>
                  <Button className="button--quiet button--small" onClick={() => void loadAssets()}>重试</Button>
                </div>
              ) : assets === null ? (
                <div className="model-loading" aria-busy="true">正在加载资产…</div>
              ) : (
                <>
                  {!canGenerate ? (
                    <div className="dialog-warning" role="note">
                      <Glyph name="warning" size={15} />
                      <span>
                        {documentMissing ? "尚未创建并定稿知识文档，无法生成资产。"
                          : doc && !doc.finalized ? "当前文档 Revision 尚未定稿，无法生成资产。"
                          : "请先在步骤二定稿知识文档，再生成资产。"}
                      </span>
                    </div>
                  ) : null}
                  <OperationReadinessNotice report={operationReadiness.GENERATE_ASSETS ?? null}
                    label="五类资产生成" onNavigate={onNavigate}/>
                  <div className="asset-toolbar">
                    <span>基于定稿 Revision {doc ? `v${doc.revisionNumber}` : "—"} 确定性生成；生成过程会真实追踪 Job。</span>
                    <Button className="button--primary button--small"
                      disabled={!canGenerate || generatingTypes !== null
                        || operationReadiness.GENERATE_ASSETS?.ready === false}
                      onClick={() => void startGeneration(["RULE_CATALOG", "DECISION_FLOW", "SKILL_PACKAGE", "QA_PAIRS", "EVALUATION_SET"])}>
                      {generatingTypes !== null ? "生成中…" : "生成全部"}
                    </Button>
                  </div>
                  {assetJob || jobStatus ? (
                    <div className="job-strip" aria-live="polite">
                      <div className={`job-strip__pulse job-strip__pulse--${jobStatus ? (jobStatus.status === "SUCCEEDED" ? "closed" : "open") : "open"}`} />
                      <div>
                        <b>资产生成任务 {assetJob?.jobId}</b>
                        <small>{jobStatus
                          ? `${jobStatus.status}${jobStatus.errorCode ? ` · ${jobStatus.errorCode}` : ""}${jobStatus.stage ? ` · ${jobStatus.stage}` : ""}`
                          : "QUEUED"} · {jobStatus?.percent ?? 0}%</small>
                      </div>
                      <progress className="job-strip__progress" max={100} value={jobStatus?.percent ?? 0} aria-label="任务进度" />
                      <strong>{jobStatus?.percent ?? 0}%</strong>
                    </div>
                  ) : null}
                  {assetAgentExecutions.length > 0 ? (
                    <div className="agent-execution-summary" aria-label="智能体执行记录">
                      <b>智能体执行</b>
                      {assetAgentExecutions.map((attempt) => (
                        <span key={attempt.id}
                          className={`agent-execution-summary__item agent-execution-summary__item--${attempt.status.toLowerCase()}`}>
                          {ASSET_TYPE_LABELS[attempt.assetType]} · {attempt.role} · {attempt.status}
                          {attempt.failureCode ? ` · ${attempt.failureCode}` : ""}
                          <code>{attempt.effectiveConfigHash.slice(0, 10)}</code>
                        </span>
                      ))}
                    </div>
                  ) : null}
                  <div className="asset-grid">
                    {assets.map((asset) => {
                      const generating = generatingTypes?.includes(asset.type) ?? false;
                      const tone = asset.status === "READY" ? "success"
                        : asset.status === "FAILED" ? "danger"
                        : asset.status === "BLOCKED" ? "warning"
                        : asset.status === "GENERATING" ? "info" : "neutral";
                      return (
                        <article key={asset.id} className={`asset-card asset-card--${asset.status.toLowerCase()}`}>
                          <div className="asset-card__number">v{asset.version}</div>
                          <div className="asset-card__head">
                            <span className="asset-symbol">{ASSET_TYPE_LABELS[asset.type]?.slice(0, 1)}</span>
                            <Status tone={tone}>{ASSET_STATUS_LABELS[asset.status] ?? asset.status}</Status>
                          </div>
                          <h3>{ASSET_TYPE_LABELS[asset.type] ?? asset.type}</h3>
                          <code>{asset.type}</code>
                          <p>{ASSET_TYPE_DESCRIPTIONS[asset.type]}</p>
                          {asset.status === "BLOCKED" ? (
                            <div className="asset-card__warning"><Glyph name="warning" size={14} />
                              {asset.type === "EVALUATION_SET"
                                ? "评测集需要至少一份 READY 的留出（HOLDOUT）素材；缺少时会阻断发布预检。"
                                : asset.failureReason || "前置条件缺失。"}
                            </div>
                          ) : null}
                          {asset.status === "FAILED" ? (
                            <div className="asset-card__warning"><Glyph name="warning" size={14} />
                              {asset.failureReason || "生成失败，可单独重试。"}
                            </div>
                          ) : null}
                          <footer>
                            <span>{asset.documentRevisionId ? "已绑定定稿 Revision" : "未绑定 Revision"}</span>
                            {asset.status === "READY" ? (
                              <a href={`/api/v1/assets/${asset.id}/download`} target="_blank" rel="noopener noreferrer">下载 Bundle</a>
                            ) : (asset.status === "FAILED" || asset.status === "BLOCKED") ? (
                              <button disabled={!canGenerate || generatingTypes !== null
                                || operationReadiness.GENERATE_ASSETS?.ready === false}
                                onClick={() => void startGeneration([asset.type])}>
                                {generating ? "生成中…" : "重试"}
                              </button>
                            ) : null}
                          </footer>
                        </article>
                      );
                    })}
                  </div>

                  <section className="release-panel">
                    <div className="release-panel__head">
                      <div><span className="release-mark"><Glyph name="lock" size={18} /></span><div><h3>发布场景快照</h3><p>累计发布所选子场景的 READY 资产与定稿文档 Revision，生成不可变 Manifest。</p></div></div>
                    </div>
                    {!canPublish ? (
                      <div className="release-panel__body-note" role="note">
                        <Glyph name="lock" size={14} /> 仅 PUBLISHER / ADMIN 可执行发布；当前账号{currentUser ? `（${currentUser.username}）` : ""}不可发布。
                      </div>
                    ) : released ? (
                      <div className="release-result">
                        <div className="release-check"><Glyph name="check" size={16} /><span>已发布 {released.tag} · {released.coverage === "FULL" ? "全覆盖" : "部分覆盖"} · Manifest sha256 {released.manifestSha256.slice(0, 16)}…</span></div>
                        <div className="release-result__actions">
                          <Button className="button--quiet button--small" onClick={() => void openManifest()}>查看 Manifest</Button>
                          <Button className="button--quiet button--small" onClick={() => void downloadManifest()}>下载 Manifest</Button>
                        </div>
                        {manifest ? <pre className="manifest-view">{manifest}</pre> : null}
                      </div>
                    ) : (
                      <div className="release-form">
                        <OperationReadinessNotice report={operationReadiness.RELEASE ?? null}
                          label="当前子场景发布" onNavigate={onNavigate}/>
                        <div className="release-baseline" role="note">
                          <Glyph name="history" size={14} /> 当前发布基线：{latestRelease ? `${latestRelease.tag}（${latestRelease.manifestSha256.slice(0, 12)}…）` : "尚无发布（首次发布）"}
                        </div>
                        <div className="coverage-table" role="table" aria-label="子场景发布选择">
                          {subscenes.map((subscene) => (
                            <div className="coverage-table__row" role="row" key={subscene.id}>
                              <span role="cell"><input type="checkbox" checked={releaseSelected.includes(subscene.id)}
                                onChange={() => setReleaseSelected((current) => current.includes(subscene.id)
                                  ? current.filter((id) => id !== subscene.id) : [...current, subscene.id])}
                                aria-label={`将${subscene.name}加入本次发布`} /></span>
                              <strong role="cell">{subscene.name}</strong>
                            </div>
                          ))}
                        </div>
                        <div className="release-form__fields">
                          <label className="field"><span>发布 tag</span><input value={releaseTag} maxLength={100} placeholder="v1.0" aria-label="发布 tag"
                            onChange={(event) => setReleaseTag(event.currentTarget.value)} /></label>
                          <label className="field"><span>发布说明</span><input value={releaseNote} maxLength={2000} placeholder="本次发布内容与变更" aria-label="发布说明"
                            onChange={(event) => setReleaseNote(event.currentTarget.value)} /></label>
                          <label className="field field--row"><span>我已核对发布内容与范围（二次确认）</span>
                            <input type="checkbox" checked={releaseConfirmed}
                              onChange={(event) => setReleaseConfirmed(event.currentTarget.checked)} aria-label="我已核对发布内容与范围" /></label>
                        </div>
                        {releaseError ? <div className="form-error" role="alert">{releaseError}</div> : null}
                        <div className="release-form__actions">
                          <Button className="button--quiet button--small" onClick={() => void runPreflight()}
                            disabled={operationReadiness.RELEASE?.ready === false}>发布预检</Button>
                          {validation ? (
                            <Button className="button--primary button--small" disabled={!validation.ready || publishing || draftStale}
                              onClick={() => void publishRelease()}>{publishing ? "发布中…" : draftStale ? "内容已变更，请重新预检" : "确认发布"}</Button>
                          ) : null}
                        </div>
                        {draftStale ? (
                          <div className="form-error" role="alert">发布内容已变更，确认发布已禁用；请重新执行发布预检。</div>
                        ) : null}
                        {validation ? (
                          <div className="preflight-result" role="status">
                            <div className={validation.ready ? "release-check" : "release-check release-check--blocked"}>
                              <Glyph name={validation.ready ? "check" : "warning"} size={16} />
                              <span>{validation.ready ? "发布预检通过" : "发布预检未通过，无法发布"}</span>
                            </div>
                            <dl className="preflight-metrics">
                              <div><dt>覆盖范围</dt><dd>{validation.coverage === "FULL" ? "全覆盖" : "部分覆盖"}</dd></div>
                              <div><dt>本次选择</dt><dd>{validation.selected.length}</dd></div>
                              <div><dt>沿用历史</dt><dd>{validation.carriedForward.length}</dd></div>
                              <div><dt>缺失</dt><dd>{validation.missing.length}</dd></div>
                            </dl>
                            {validation.blockers.length > 0 ? (
                              <ul className="preflight-list preflight-list--blockers" aria-label="阻断项">
                                {validation.blockers.map((blocker) => <li key={blocker}>{blocker}</li>)}
                              </ul>
                            ) : null}
                            {validation.warnings.length > 0 ? (
                              <ul className="preflight-list" aria-label="警告">
                                {validation.warnings.map((warning) => <li key={warning}>{warning}</li>)}
                              </ul>
                            ) : null}
                          </div>
                        ) : null}
                      </div>
                    )}
                  </section>

                  <section className="evaluation-panel" aria-labelledby="evaluation-panel-title">
                    <header className="evaluation-panel__head">
                      <div>
                        <span className="evaluation-mark"><Glyph name="check" size={18} /></span>
                        <div>
                          <span className="eyebrow">RELEASE EVIDENCE</span>
                          <h3 id="evaluation-panel-title">真实留出集评测</h3>
                          <p>固定 Release、QA_EVALUATOR、Skill 与 Holdout case 集后执行；只有成功完成才显示准确率。</p>
                        </div>
                      </div>
                      <Button className="button--primary button--small"
                        disabled={!canEvaluate || !latestRelease || !currentRoundId || readyHoldoutCount === 0 || evaluating
                          || operationReadiness.EVALUATE?.ready === false}
                        onClick={() => void runEvaluation()}>
                        {evaluating ? "评测中…" : activeEvaluation ? "重新评测" : "运行评测"}
                      </Button>
                    </header>

                    <OperationReadinessNotice report={operationReadiness.EVALUATE ?? null}
                      label="留出集评测" onNavigate={onNavigate}/>

                    {!latestRelease || readyHoldoutCount === 0 ? (
                      <div className="evaluation-prerequisite" role="note">
                        <Glyph name="warning" size={14} />
                        <span>{!latestRelease
                          ? "请先发布包含五类 READY 资产的场景快照。"
                          : "当前轮次没有 READY 的 LABELED_HOLDOUT 素材，不能运行或显示评测指标。"}</span>
                      </div>
                    ) : null}
                    {evaluationError ? <div className="form-error" role="alert">{evaluationError}</div> : null}
                    {evaluationJob ? (
                      <div className="job-strip evaluation-job" aria-live="polite">
                        <div className={`job-strip__pulse job-strip__pulse--${evaluationJobStatus?.status === "SUCCEEDED" ? "closed" : "open"}`} />
                        <div><b>评测任务 {evaluationJob.jobId}</b><small>{evaluationJobStatus?.status ?? evaluationJob.status} · {evaluationJobStatus?.percent ?? 0}%</small></div>
                        <progress className="job-strip__progress" max={100} value={evaluationJobStatus?.percent ?? 0} aria-label="评测任务进度" />
                        <strong>{evaluationJobStatus?.percent ?? 0}%</strong>
                      </div>
                    ) : null}

                    {activeEvaluation ? (
                      <div className="evaluation-evidence">
                        <div className="evaluation-evidence__rail" aria-label="评测证据链">
                          <article>
                            <span>01 · RELEASE</span>
                            <b>{latestRelease?.tag ?? "—"}</b>
                            <code>{latestRelease?.manifestSha256.slice(0, 12) ?? "—"}…</code>
                          </article>
                          <span className="evaluation-evidence__connector" aria-hidden="true">→</span>
                          <article>
                            <span>02 · SEALED HOLDOUT</span>
                            <b>{activeEvaluation.totalCases || "—"} cases</b>
                            <code>{activeEvaluation.caseSetHash ? `${activeEvaluation.caseSetHash.slice(0, 12)}…` : "冻结中"}</code>
                          </article>
                          <span className="evaluation-evidence__connector" aria-hidden="true">→</span>
                          <article>
                            <span>03 · RESULTS</span>
                            <b>{activeEvaluation.passedCases} pass / {activeEvaluation.failedCases + activeEvaluation.errorCases} fail</b>
                            <Status tone={activeEvaluation.status === "SUCCEEDED" ? "success"
                              : activeEvaluation.status === "FAILED" ? "danger"
                              : activeEvaluation.status === "CANCELLED" ? "warning" : "info"}>
                              {EVALUATION_STATUS_LABELS[activeEvaluation.status]}
                            </Status>
                          </article>
                          <article className="evaluation-evidence__metric">
                            <span>ACCURACY</span>
                            <strong>{activeEvaluation.status === "SUCCEEDED" && activeEvaluation.accuracy !== null
                              ? `${(activeEvaluation.accuracy * 100).toFixed(1)}%` : "—"}</strong>
                            <small>{activeEvaluation.status === "SUCCEEDED" ? "真实 Holdout 结果" : "完成后回流"}</small>
                          </article>
                        </div>

                        {activeEvaluation.failureCode ? (
                          <div className="evaluation-prerequisite" role="alert">
                            <Glyph name="warning" size={14} /> {activeEvaluation.failureCode}
                          </div>
                        ) : null}
                        {evaluationDetail?.cases.length ? (
                          <div className="evaluation-cases" role="table" aria-label="评测用例证据">
                            <div className="evaluation-cases__row evaluation-cases__head" role="row">
                              <span role="columnheader">Case / 来源</span>
                              <span role="columnheader">输入</span>
                              <span role="columnheader">期望 → 预测</span>
                              <span role="columnheader">结果</span>
                            </div>
                            {evaluationDetail.cases.map((item) => (
                              <div className="evaluation-cases__row" role="row" key={item.id}>
                                <span role="cell"><b>{item.caseKey}</b><code>{item.sourceRefCode}</code></span>
                                <span role="cell">{item.input}</span>
                                <span role="cell"><b>{item.expected}</b><i aria-hidden="true">→</i><b>{item.prediction ?? "—"}</b></span>
                                <span role="cell"><Status tone={item.outcome === "PASSED" ? "success"
                                  : item.outcome === "ERROR" ? "danger" : item.outcome === "FAILED" ? "warning" : "neutral"}>
                                  {item.outcome ?? "PENDING"}
                                </Status></span>
                              </div>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    ) : (
                      <EmptyState title="还没有评测证据" detail="发布后使用隔离留出集运行评测；平台不会展示模拟准确率。" />
                    )}
                  </section>
                </>
              )}
            </section>
          ) : null}
        </div>
      </div>

      {dialogOpen ? (
        <AddSubSceneDialog
          saving={subsceneSaving}
          formError={subsceneFormError}
          formFieldErrors={subsceneFieldErrors}
          onClose={closeDialog}
          onSubmit={(event) => void submitSubScene(event)}
        />
      ) : null}
      {uploadOpen && selectedSubsceneId && latestRound ? (
        <UploadMaterialDialog
          roundId={latestRound.id}
          subSceneId={selectedSubsceneId}
          onClose={() => setUploadOpen(false)}
          onUploaded={(accepted) => {
            refreshedMaterialEvent.current = null;
            setMaterialJob(accepted);
            void loadMaterials();
          }}
        />
      ) : null}
    </div>
  );
}
