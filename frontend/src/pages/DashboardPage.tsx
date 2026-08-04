import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, EmptyState, Glyph, PageHeader } from "../components/Ui";
import { ApiError, createScene, listScenes } from "../lib/api";
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

  return (
    <div className="page dashboard-page">
      <PageHeader
        eyebrow="工作台 / 场景库"
        title="知识正在形成可追溯的资产"
        description="从原始素材到发布快照，每一次萃取、人工确认和生成结果都保留版本关系。"
        actions={
          <Button className="button--primary" onClick={openDialog}>
            <Glyph name="plus" />新建萃取场景
          </Button>
        }
      />

      {loadError ? (
        <div className="load-error" role="alert">
          <Glyph name="warning" size={16} />
          <div><b>无法加载场景列表</b><span>{loadError}</span></div>
          <Button className="button--quiet button--small" onClick={() => void loadScenes(currentPage)}>重试</Button>
        </div>
      ) : null}
      {scenes === null && !loadError ? (
        <div className="model-loading" aria-busy="true">正在加载场景列表…</div>
      ) : null}
      {scenes !== null && scenes.length === 0 ? (
        <EmptyState title="还没有场景" detail="点击右上角“新建萃取场景”创建第一个场景。" />
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
                  <footer>
                    <span>打开场景 →</span>
                  </footer>
                </article>
              ))}
              <button className="new-scene-card" onClick={openDialog}>
                <span><Glyph name="plus" size={22} /></span><b>新建萃取场景</b><small>从目标和素材开始</small>
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
    </div>
  );
}
