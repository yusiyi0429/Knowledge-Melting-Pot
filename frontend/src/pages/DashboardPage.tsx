import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, EmptyState, Glyph, PageHeader } from "../components/Ui";
import { ApiError, createScene, deleteScene, listScenes } from "../lib/api";
import type { Scene } from "../lib/api";

const PAGE_SIZE = 20;

function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  });
}

function fieldErrorsToRecord(errors: ApiError["errors"]): Record<string, string> {
  const record: Record<string, string> = {};
  for (const error of errors ?? []) {
    if (!record[error.field]) record[error.field] = error.message;
  }
  return record;
}

function CreateSceneDialog({
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
    <dialog ref={ref} className="scene-dialog" aria-labelledby="scene-dialog-title" onCancel={onClose}>
      <form className="model-dialog__form" onSubmit={onSubmit} noValidate>
        <header className="model-dialog__head">
          <div>
            <h2 id="scene-dialog-title">新建萃取场景</h2>
            <p>场景用于组织素材、Revision、资产和发布快照；名称必填。</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={saving}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          <label className="field">
            <span>场景名称</span>
            <input name="name" autoFocus autoComplete="off" maxLength={200}
              placeholder="例如：对公贷款五级分类" aria-invalid={Boolean(formFieldErrors.name)} />
            <small>1–200 字符。</small>
            {formFieldErrors.name ? <small className="field-error">{formFieldErrors.name}</small> : null}
          </label>
          <label className="field">
            <span>描述（可选）</span>
            <textarea name="description" maxLength={10000}
              placeholder="说明该场景的业务边界与期望产物。" aria-invalid={Boolean(formFieldErrors.description)} />
            {formFieldErrors.description ? <small className="field-error">{formFieldErrors.description}</small> : null}
          </label>
          {formError ? <div className="form-error" role="alert">{formError}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={saving}>取消</Button>
          <Button type="submit" className="button--primary" disabled={saving}>
            {saving ? "创建中…" : "创建场景"}
          </Button>
        </footer>
      </form>
    </dialog>
  );
}

function DeleteSceneDialog({
  scene,
  deleting,
  error,
  onClose,
  onConfirm,
}: {
  scene: Scene;
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
    <dialog ref={ref} className="scene-dialog scene-delete-dialog" aria-labelledby="scene-delete-title"
      onCancel={onClose}>
      <div className="model-dialog__form">
        <header className="model-dialog__head">
          <div>
            <h2 id="scene-delete-title">删除场景</h2>
            <p>场景会从工作台移除，已有知识链路仍保留用于审计和追溯。</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={deleting}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body scene-delete-dialog__body">
          <div className="scene-delete-dialog__target">
            <span>即将删除</span>
            <strong>{scene.name}</strong>
            <code>{scene.id}</code>
          </div>
          <div className="scene-delete-dialog__note">
            <Glyph name="warning" size={17} />
            <p><b>不会物理擦除历史数据</b><span>子场景、素材、Revision、资产、发布快照和审计记录将继续保留。</span></p>
          </div>
          {error ? <div className="form-error" role="alert">{error}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={deleting}>取消</Button>
          <Button type="button" className="button--danger" onClick={onConfirm} disabled={deleting}>
            {deleting ? "删除中…" : "确认删除场景"}
          </Button>
        </footer>
      </div>
    </dialog>
  );
}

export function DashboardPage({ onNavigate }: { onNavigate: (href: string) => void }) {
  const [scenes, setScenes] = useState<Scene[] | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [total, setTotal] = useState<number | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [query, setQuery] = useState("");

  const [dialogOpen, setDialogOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [formFieldErrors, setFormFieldErrors] = useState<Record<string, string>>({});
  const [deleteTarget, setDeleteTarget] = useState<Scene | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const dialogTrigger = useRef<HTMLElement | null>(null);

  const loadScenes = useCallback(async (page: number) => {
    setLoadError(null);
    try {
      const result = await listScenes(page, PAGE_SIZE);
      setScenes(result.items);
      setTotal(result.total);
      setCurrentPage(result.page);
    } catch (reason) {
      setScenes(null);
      setTotal(null);
      setLoadError(reason instanceof ApiError ? reason.message : "无法连接 API，请确认已登录后重试。");
    }
  }, []);

  useEffect(() => {
    void loadScenes(0);
  }, [loadScenes]);

  const visibleScenes = useMemo(() => {
    if (scenes === null) return [];
    const needle = query.trim().toLowerCase();
    if (!needle) return scenes;
    return scenes.filter((scene) =>
      scene.name.toLowerCase().includes(needle) || scene.description.toLowerCase().includes(needle));
  }, [scenes, query]);

  const pageCount = total === null ? null : Math.max(1, Math.ceil(total / PAGE_SIZE));
  const canGoPrevious = currentPage > 0;
  const canGoNext = pageCount !== null && currentPage + 1 < pageCount;

  const goToPage = (page: number) => {
    setQuery("");
    void loadScenes(page);
  };

  const openDialog = () => {
    dialogTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setNotice(null);
    setFormError(null);
    setFormFieldErrors({});
    setDialogOpen(true);
  };

  const closeDialog = () => {
    setDialogOpen(false);
    dialogTrigger.current?.focus();
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    const form = event.currentTarget;
    const formData = new FormData(form);
    const name = String(formData.get("name") ?? "").trim();
    const description = String(formData.get("description") ?? "").trim();
    if (!name) {
      setFormError("请输入场景名称。");
      return;
    }
    setSaving(true);
    try {
      const created = await createScene({ name, description: description || undefined });
      closeDialog();
      await loadScenes(currentPage);
      onNavigate(`/scenes/${created.id}`);
    } catch (reason) {
      if (reason instanceof ApiError) {
        setFormError(reason.message);
        setFormFieldErrors(fieldErrorsToRecord(reason.errors));
      } else {
        setFormError("创建失败，请稍后重试。");
      }
    } finally {
      setSaving(false);
    }
  };

  const confirmDelete = async () => {
    if (!deleteTarget || deleting) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      await deleteScene(deleteTarget.id);
      const remaining = Math.max(0, (total ?? scenes?.length ?? 1) - 1);
      const lastPage = Math.max(0, Math.ceil(remaining / PAGE_SIZE) - 1);
      setDeleteTarget(null);
      setNotice(`“${deleteTarget.name}”已从工作台删除，历史链路仍保留。`);
      await loadScenes(Math.min(currentPage, lastPage));
    } catch (reason) {
      setDeleteError(reason instanceof ApiError ? reason.message : "删除失败，请稍后重试。");
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="page dashboard-page">
      <PageHeader
        eyebrow="工作台 / 场景库"
        title="知识萃取场景"
        description="管理从业务素材、规则萃取到资产发布的完整链路。每一步都保留版本、来源和操作记录。"
        actions={<>
          <Button className="button--quiet" onClick={() => onNavigate("/explore")}>
            <Glyph name="search" />从素材发现场景
          </Button>
          <Button className="button--primary" onClick={openDialog}>
            <Glyph name="plus" />直接创建场景
          </Button>
        </>}
      />

      <section className="scene-entry-choice" aria-label="场景启动方式">
        <button type="button" onClick={() => onNavigate("/explore")}>
          <span>STEP 0 · 可选</span><div><Glyph name="search" size={18}/><b>从素材发现场景</b></div>
          <small>尚未确定业务边界时，先上传素材，由场景探索智能体生成候选。</small>
        </button>
        <button type="button" onClick={openDialog}>
          <span>直接进入 STEP 1</span><div><Glyph name="plus" size={18}/><b>直接创建场景</b></div>
          <small>目标已经明确时，创建 Scene / SubScene / Round 后进入素材固定。</small>
        </button>
      </section>

      <section className="dashboard-ledger" aria-label="知识萃取流程">
        <div className="dashboard-ledger__summary">
          <span>SCENE LEDGER</span>
          <strong>{total ?? "—"}</strong>
          <p>个场景正在工作台中管理</p>
        </div>
        <ol className="dashboard-ledger__flow">
          <li><span>01</span><div><b>固定素材</b><small>锁定轮次、子场景和用途分区</small></div></li>
          <li><span>02</span><div><b>萃取规则</b><small>生成可校验文档与来源引用</small></div></li>
          <li><span>03</span><div><b>生成资产</b><small>五类资产独立生成、独立重试</small></div></li>
          <li><span>04</span><div><b>发布快照</b><small>以不可变 Manifest 留存完整链路</small></div></li>
        </ol>
      </section>

      {loadError ? (
        <div className="load-error" role="alert">
          <Glyph name="warning" size={16} />
          <div><b>无法加载场景列表</b><span>{loadError}</span></div>
          <Button className="button--quiet button--small" onClick={() => void loadScenes(currentPage)}>重试</Button>
        </div>
      ) : null}
      {notice ? <div className="page-notice" role="status"><Glyph name="check" size={15} />{notice}</div> : null}
      {scenes === null && !loadError ? (
        <div className="model-loading" aria-busy="true">正在加载场景列表…</div>
      ) : null}
      {scenes !== null && scenes.length === 0 ? (
        <EmptyState title="还没有场景" detail="可以先从素材发现候选，也可以直接创建已知场景。" />
      ) : null}
      {scenes !== null && scenes.length > 0 ? (
        <section className="section-block">
          <div className="section-head section-head--compact">
            <div><h2>场景清单</h2><p>共 {total ?? scenes.length} 个场景</p></div>
            <div className="section-head__tools">
              <label className="inline-search">
                <span className="sr-only">搜索场景</span>
                <Glyph name="search" size={16} />
                <input type="search" placeholder="按名称或描述过滤…" value={query}
                  onChange={(event) => setQuery(event.currentTarget.value)} />
              </label>
              <div className="pagination">
                <Button className="button--quiet button--small" disabled={!canGoPrevious}
                  onClick={() => goToPage(currentPage - 1)}>上一页</Button>
                <span>{pageCount === null ? "第 X 页" : `第 ${currentPage + 1} 页 / 共 ${pageCount} 页`}</span>
                <Button className="button--quiet button--small" disabled={!canGoNext}
                  onClick={() => goToPage(currentPage + 1)}>下一页</Button>
              </div>
            </div>
          </div>
          {visibleScenes.length === 0 ? (
            <EmptyState title="没有匹配的场景" detail="换个关键词试试。" />
          ) : (
            <div className="scene-grid">
              {visibleScenes.map((scene) => (
                <article className="scene-card" key={scene.id}>
                  <button className="scene-card__target" aria-label={`打开${scene.name}`}
                    onClick={() => onNavigate(`/scenes/${scene.id}`)} />
                  <div className="scene-card__top">
                    <span className="round-chip">{formatDateTime(scene.updatedAt)} 更新</span>
                  </div>
                  <h3>{scene.name}</h3>
                  <p>{scene.description || "暂无描述"}</p>
                  <div className="scene-card__lineage">
                    <span>创建于 {formatDateTime(scene.createdAt)}</span>
                  </div>
                  <footer className="scene-card__footer">
                    <button type="button" className="scene-card__delete" aria-label={`删除${scene.name}`}
                      onClick={() => { setNotice(null); setDeleteError(null); setDeleteTarget(scene); }}>删除</button>
                    <span>打开场景 →</span>
                  </footer>
                </article>
              ))}
              <button className="new-scene-card" onClick={openDialog}>
                <span><Glyph name="plus" size={22} /></span><b>直接创建场景</b><small>从已知业务目标开始</small>
              </button>
            </div>
          )}
        </section>
      ) : null}
      {dialogOpen ? (
        <CreateSceneDialog
          saving={saving}
          formError={formError}
          formFieldErrors={formFieldErrors}
          onClose={closeDialog}
          onSubmit={(event) => void submit(event)}
        />
      ) : null}
      {deleteTarget ? (
        <DeleteSceneDialog scene={deleteTarget} deleting={deleting} error={deleteError}
          onClose={() => { if (!deleting) setDeleteTarget(null); }} onConfirm={() => void confirmDelete()} />
      ) : null}
    </div>
  );
}
