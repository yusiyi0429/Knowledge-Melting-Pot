import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, EmptyState, Glyph, PageHeader, Status } from "../components/Ui";
import { OperationReadinessNotice } from "../components/OperationReadinessNotice";
import { useJobEvents } from "../hooks/useJobEvents";
import {
  acceptExplorationCandidate,
  ApiError,
  createExploration,
  deleteExploration,
  getJob,
  getExploration,
  getOperationReadiness,
  listExplorations,
  retryJob,
  startExploration,
} from "../lib/api";
import type { ExplorationDetail, ExplorationSession, OperationReadiness } from "../lib/api";
import { UploadMaterialDialog } from "./UploadMaterialDialog";

const STATUS_LABEL: Record<ExplorationSession["status"], string> = {
  DRAFT: "准备素材",
  ANALYZING: "正在探索",
  READY: "候选已就绪",
  ACCEPTED: "已进入正式流程",
  FAILED: "探索未完成",
  CANCELLED: "已取消",
};

const FAILURE_MESSAGE: Record<string, string> = {
  EXPLORATION_CANDIDATES_EMPTY: "模型没有返回可用候选，系统已完成一次格式修复尝试。",
  EXPLORATION_CANDIDATE_LIMIT_EXCEEDED: "模型返回的候选数量超过 5 个。",
  EXPLORATION_SOURCE_REFERENCE_INVALID: "候选引用了本次素材集合之外的来源。",
  EXPLORATION_SOURCE_REFERENCE_MISSING: "候选没有引用任何可信素材。",
  EXPLORATION_REQUIRED_FIELD_MISSING: "候选缺少场景名称、子场景名称或业务依据。",
  EXPLORATION_FIELD_TOO_LONG: "候选字段超过平台允许的长度。",
  EXPLORATION_VALUE_LEVEL_INVALID: "候选价值等级不符合约定。",
  MODEL_JSON_INVALID: "模型没有返回可解析的结构化 JSON。",
};

function statusTone(status: ExplorationSession["status"]): "neutral" | "success" | "warning" | "danger" | "info" {
  if (status === "READY" || status === "ACCEPTED") return "success";
  if (status === "ANALYZING") return "info";
  if (status === "FAILED" || status === "CANCELLED") return "danger";
  return "neutral";
}

function formatBytes(value: number): string {
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

function DeleteExplorationDialog({
  session,
  deleting,
  error,
  onClose,
  onConfirm,
}: {
  session: ExplorationSession;
  deleting: boolean;
  error: string | null;
  onClose: () => void;
  onConfirm: () => void;
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
    <dialog ref={ref} className="scene-dialog scene-delete-dialog" aria-labelledby="exploration-delete-title"
      onCancel={onClose}>
      <div className="model-dialog__form">
        <header className="model-dialog__head">
          <div>
            <h2 id="exploration-delete-title">删除探索记录</h2>
            <p>记录会从探索账本移除，证据素材、任务事件和审计链路仍会保留。</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={deleting}>
            <Glyph name="close" size={16}/>
          </button>
        </header>
        <div className="model-dialog__body scene-delete-dialog__body">
          <div className="scene-delete-dialog__target">
            <span>即将删除</span>
            <strong>{session.title}</strong>
            <code>EXP-{session.id}</code>
          </div>
          <div className="scene-delete-dialog__note">
            <Glyph name="warning" size={17}/>
            <p><b>不会物理擦除历史数据</b><span>已接受候选创建的正式场景不会受到影响。</span></p>
          </div>
          {error ? <div className="form-error" role="alert">{error}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={deleting}>取消</Button>
          <Button type="button" className="button--danger" onClick={onConfirm} disabled={deleting}>
            {deleting ? "删除中…" : "确认删除记录"}
          </Button>
        </footer>
      </div>
    </dialog>
  );
}

export function ExplorationPage({ onNavigate }: { onNavigate: (href: string) => void }) {
  const [sessions, setSessions] = useState<ExplorationSession[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ExplorationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [creating, setCreating] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ExplorationSession | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [failureCode, setFailureCode] = useState<string | null>(null);
  const [readiness, setReadiness] = useState<OperationReadiness | null>(null);

  const loadDetail = useCallback(async (id: string) => {
    try {
      const next = await getExploration(id);
      setDetail(next);
      setSessions((current) => current.map((item) => item.id === id ? next.session : item));
      if (next.session.status === "FAILED" && next.session.exploreJobId) {
        try {
          const job = await getJob(next.session.exploreJobId);
          setFailureCode(job.errorCode);
        } catch {
          setFailureCode(null);
        }
      } else {
        setFailureCode(null);
      }
      setError(null);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "无法读取探索详情。");
    }
  }, []);

  const loadSessions = useCallback(async () => {
    setLoading(true);
    try {
      const loaded = await listExplorations();
      setSessions(loaded);
      const nextId = selectedId && loaded.some((item) => item.id === selectedId)
        ? selectedId
        : loaded.find((item) => item.status !== "ACCEPTED")?.id ?? loaded[0]?.id ?? null;
      setSelectedId(nextId);
      if (nextId) await loadDetail(nextId);
      else setDetail(null);
      setError(null);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "无法连接 API。");
    } finally {
      setLoading(false);
    }
  }, [loadDetail, selectedId]);

  useEffect(() => { void loadSessions(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!selectedId) return;
    void loadDetail(selectedId);
  }, [loadDetail, selectedId]);

  useEffect(() => {
    if (!detail || detail.session.status !== "DRAFT") {
      setReadiness(null);
      return;
    }
    let active = true;
    void getOperationReadiness("SCENE_EXPLORE", { explorationSessionId: detail.session.id })
      .then((value) => { if (active) setReadiness(value); })
      .catch(() => { if (active) setReadiness(null); });
    return () => { active = false; };
  }, [detail?.session.id, detail?.session.status, detail?.materials.map((item) => item.status).join("|")]);

  const activeJobId = detail?.session.status === "ANALYZING" ? detail.session.exploreJobId : null;
  const { events, connection } = useJobEvents({ jobId: activeJobId });
  const lastEvent = events.at(-1);

  useEffect(() => {
    if (!detail) return;
    const needsPolling = detail.session.status === "ANALYZING"
      || detail.materials.some((item) => !["READY", "FAILED", "INACTIVE"].includes(item.status));
    if (!needsPolling) return;
    const timer = window.setInterval(() => void loadDetail(detail.session.id), 1800);
    return () => window.clearInterval(timer);
  }, [detail, loadDetail]);

  useEffect(() => {
    if (lastEvent?.type === "completed" || lastEvent?.type === "failed") {
      if (detail) void loadDetail(detail.session.id);
    }
  }, [detail?.session.id, lastEvent?.type, loadDetail]);

  const create = async (event: FormEvent) => {
    event.preventDefault();
    const title = newTitle.trim();
    if (!title || creating) return;
    setCreating(true);
    setError(null);
    try {
      const created = await createExploration(title);
      setSessions((current) => [created, ...current]);
      setSelectedId(created.id);
      setNewTitle("");
      await loadDetail(created.id);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "创建探索失败。");
    } finally {
      setCreating(false);
    }
  };

  const analyze = async () => {
    if (!detail || busy) return;
    setBusy(true);
    setError(null);
    try {
      await startExploration(detail.session.id, crypto.randomUUID());
      await loadDetail(detail.session.id);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "启动探索失败。");
    } finally {
      setBusy(false);
    }
  };

  const retryAnalysis = async () => {
    if (!detail?.session.exploreJobId || detail.session.status !== "FAILED" || retrying) return;
    setRetrying(true);
    setError(null);
    try {
      const jobId = detail.session.exploreJobId;
      await retryJob(jobId, crypto.randomUUID());
      for (let attempt = 0; attempt < 60; attempt += 1) {
        const job = await getJob(jobId);
        await loadDetail(detail.session.id);
        if (["SUCCEEDED", "FAILED", "CANCELLED"].includes(job.status)) break;
        await new Promise((resolve) => window.setTimeout(resolve, 750));
      }
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "重新探索失败，请检查任务通知。" );
    } finally {
      setRetrying(false);
    }
  };

  const confirmDelete = async () => {
    if (!deleteTarget || deleting) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      const deletedId = deleteTarget.id;
      await deleteExploration(deletedId);
      setDeleteTarget(null);
      if (selectedId === deletedId) {
        setSelectedId(null);
        setDetail(null);
      }
      await loadSessions();
    } catch (reason) {
      setDeleteError(reason instanceof ApiError ? reason.message : "删除失败，请稍后重试。");
    } finally {
      setDeleting(false);
    }
  };

  const accept = async (candidateId: string) => {
    if (!detail || busy) return;
    setBusy(true);
    setError(null);
    try {
      const accepted = await acceptExplorationCandidate(detail.session.id, candidateId, detail.etag);
      await loadDetail(detail.session.id);
      onNavigate(`/scenes/${accepted.sceneId}`);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "接受候选失败。");
    } finally {
      setBusy(false);
    }
  };

  const readyForAnalysis = useMemo(() => Boolean(detail && detail.materials.length > 0
    && detail.materials.every((item) => item.status === "READY") && (readiness?.ready ?? true)), [detail, readiness]);

  return (
    <div className="page exploration-page">
      <PageHeader
        eyebrow="STEP 0 · 可选 / 场景发现"
        title="从证据边界中发现候选场景"
        description="仅在业务边界尚不明确时使用。接受候选后复用原文件、Chunk 与来源定位，并从正式流程 STEP 1 继续。"
        actions={<Button className="button--quiet" onClick={() => onNavigate("/")}><Glyph name="grid" />返回场景库</Button>}
      />

      {error ? <div className="load-error" role="alert"><Glyph name="warning" size={16}/><div><b>当前操作未完成</b><span>{error}</span></div></div> : null}

      <section className="exploration-console">
        <aside className="exploration-ledger" aria-label="探索记录">
          <div className="exploration-ledger__head">
            <div><span>探索账本</span><b>{sessions.length}</b></div>
            <small>最近 50 条</small>
          </div>
          <form className="exploration-create" onSubmit={(event) => void create(event)}>
            <label><span className="sr-only">探索主题</span><input value={newTitle} maxLength={200}
              onChange={(event) => setNewTitle(event.currentTarget.value)} placeholder="输入探索主题…" /></label>
            <Button className="button--primary button--small" disabled={!newTitle.trim() || creating}>
              <Glyph name="plus" size={14}/>{creating ? "创建中" : "新探索"}
            </Button>
          </form>
          {loading ? <div className="model-loading">正在读取探索记录…</div> : null}
          {!loading && sessions.length === 0 ? <EmptyState title="暂无探索" detail="先创建一个主题，再添加素材。" /> : null}
          <div className="exploration-ledger__list">
            {sessions.map((session) => <div key={session.id}
              className={`exploration-ledger__item ${selectedId === session.id ? "active" : ""}`}>
              <button type="button" className="exploration-ledger__select" onClick={() => setSelectedId(session.id)}>
                <span>{session.title}</span>
                <small>{STATUS_LABEL[session.status]} · {formatTime(session.updatedAt)}</small>
              </button>
              {session.status !== "ANALYZING" ? <button type="button" className="exploration-ledger__delete"
                aria-label={`删除探索记录：${session.title}`} title="删除探索记录"
                onClick={() => { setDeleteError(null); setDeleteTarget(session); }}>
                <Glyph name="trash" size={12}/><span>删除</span>
              </button> : null}
            </div>)}
          </div>
        </aside>

        <div className="exploration-workspace">
          {!detail && !loading ? <EmptyState title="创建一次场景探索" detail="上传业务素材后，场景探索智能体会生成带来源范围的候选。" /> : null}
          {detail ? <>
            <header className="exploration-workspace__head">
              <div><span className="eyebrow">EXP-{detail.session.id.slice(0, 8)}</span><h2>{detail.session.title}</h2></div>
              <div className="exploration-workspace__head-actions">
                <Status tone={statusTone(detail.session.status)}>{STATUS_LABEL[detail.session.status]}</Status>
                {detail.session.status !== "ANALYZING" ? <Button type="button"
                  className="button--quiet button--small exploration-delete-action"
                  onClick={() => { setDeleteError(null); setDeleteTarget(detail.session); }}>
                  <Glyph name="trash" size={13}/>删除记录
                </Button> : null}
              </div>
            </header>

            <div className="evidence-constellation" aria-label="探索证据路径">
              <section className="evidence-stratum">
                <span className="evidence-stratum__index">01</span>
                <div className="evidence-stratum__title"><b>STAGING EVIDENCE</b><span>隔离素材</span></div>
                <div className="evidence-stratum__body">
                  {detail.materials.length === 0 ? <p>尚未放入素材。</p> : detail.materials.map((material) =>
                    <article key={material.id} className="staged-material">
                      <Glyph name="file" size={16}/><div><b>{material.fileName}</b><small>{material.format} · {formatBytes(material.sizeBytes)}</small></div>
                      <Status tone={material.status === "READY" ? "success" : material.status === "FAILED" ? "danger" : "info"}>{material.status}</Status>
                    </article>)}
                  {detail.session.status === "DRAFT" ? <Button className="button--quiet button--small" onClick={() => setUploadOpen(true)}>
                    <Glyph name="plus" size={14}/>添加素材
                  </Button> : null}
                </div>
              </section>

              <section className="evidence-stratum evidence-stratum--agent">
                <span className="evidence-stratum__index">02</span>
                <div className="evidence-stratum__title"><b>SCENE EXPLORER</b><span>受控分析</span></div>
                <div className="evidence-stratum__body">
                  <div className="agent-trace">
                    <span className={detail.session.status === "ANALYZING" ? "agent-trace__pulse" : ""}><Glyph name="bot" size={20}/></span>
                    <div><b>只读已验证 Chunk</b><p>{lastEvent ? `${lastEvent.stage} · ${lastEvent.percent}%` : "冻结模型、Skill 和素材集合后生成结构化候选。"}</p></div>
                  </div>
                  {detail.session.status === "DRAFT" ? <OperationReadinessNotice report={readiness}
                    label="场景探索" onNavigate={onNavigate}/> : null}
                  {detail.session.status === "DRAFT" ? <Button className="button--primary" disabled={!readyForAnalysis || busy} onClick={() => void analyze()}>
                    <Glyph name="play" size={15}/>{busy ? "正在启动…" : "开始探索"}
                  </Button> : null}
                  {detail.session.status === "ANALYZING" ? <div className="exploration-progress"><progress max={100} value={lastEvent?.percent ?? 5}/><span>{connection === "open" ? "实时连接" : "正在重连"}</span></div> : null}
                  {detail.session.status === "FAILED" ? <div className="exploration-failure">
                    <div><b>本次探索未生成有效候选</b>
                      <p>{failureCode ? FAILURE_MESSAGE[failureCode] ?? "任务执行失败，请根据错误码检查模型输出。" : "任务执行失败，请查看任务通知。"}</p>
                      {failureCode ? <code>{failureCode}</code> : null}</div>
                    <div><Button className="button--primary button--small" disabled={retrying}
                      onClick={() => void retryAnalysis()}><Glyph name="play" size={13}/>{retrying ? "重新探索中…" : "修复后重试"}</Button>
                      <Button className="button--quiet button--small exploration-delete-action"
                        onClick={() => { setDeleteError(null); setDeleteTarget(detail.session); }}>
                        <Glyph name="trash" size={13}/>删除记录
                      </Button></div>
                  </div> : null}
                </div>
              </section>

              <section className="evidence-stratum evidence-stratum--candidates">
                <span className="evidence-stratum__index">03</span>
                <div className="evidence-stratum__title"><b>CANDIDATE MAP</b><span>候选地图</span></div>
                <div className="evidence-stratum__body">
                  {detail.candidates.length === 0 ? <p>完成分析后，候选将按价值排序出现在这里。</p> : detail.candidates.map((candidate) =>
                    <article key={candidate.id} className="candidate-card">
                      <header><span>#{String(candidate.rank).padStart(2, "0")}</span><Status tone={candidate.valueLevel === "HIGH" ? "success" : "warning"}>{candidate.valueLevel}</Status></header>
                      <h3>{candidate.sceneName}</h3><b>{candidate.subSceneName}</b>
                      <p>{candidate.rationale}</p>
                      <div className="candidate-card__metrics"><span>{candidate.estimatedRuleCount} 条规则</span><span>{candidate.estimatedFlowCount} 个流程</span><span>{candidate.materialIds.length} 份来源</span></div>
                      <footer><div>{candidate.tags.map((tag) => <code key={tag}>{tag}</code>)}</div>
                        {detail.session.status === "READY" ? <Button className="button--primary button--small" disabled={busy} onClick={() => void accept(candidate.id)}>接受并进入萃取</Button> : null}
                      </footer>
                    </article>)}
                  {detail.acceptance ? <div className="accepted-lineage"><Glyph name="link" size={16}/><span>已复用素材并创建正式 Scene / SubScene / Round。</span><Button className="button--quiet button--small" onClick={() => onNavigate(`/scenes/${detail.acceptance?.sceneId}`)}>打开场景</Button></div> : null}
                </div>
              </section>
            </div>
          </> : null}
        </div>
      </section>

      {uploadOpen && detail ? <UploadMaterialDialog explorationSessionId={detail.session.id}
        onClose={() => setUploadOpen(false)} onUploaded={() => void loadDetail(detail.session.id)} /> : null}
      {deleteTarget ? <DeleteExplorationDialog session={deleteTarget} deleting={deleting} error={deleteError}
        onClose={() => { if (!deleting) setDeleteTarget(null); }} onConfirm={() => void confirmDelete()} /> : null}
    </div>
  );
}
