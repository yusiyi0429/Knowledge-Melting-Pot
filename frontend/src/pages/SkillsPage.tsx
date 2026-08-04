import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, EmptyState, Glyph, PageHeader, Status } from "../components/Ui";
import {
  ApiError,
  createSkill,
  createSkillVersion,
  forkSkillInstance,
  getCurrentUser,
  listScenes,
  listSkills,
} from "../lib/api";
import type { AuthenticatedUser, Scene, Skill, SkillKind } from "../lib/api";

function shortId(id: string | null): string {
  if (!id) return "—";
  return id.length > 8 ? id.slice(0, 8) : id;
}

function validateManifestInput(manifest: string): string | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(manifest);
  } catch {
    return "Manifest 必须是合法 JSON。";
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return "Manifest 必须是 JSON 对象。";
  }
  const mode = (parsed as Record<string, unknown>).executionMode;
  if (mode !== "RESOURCE_ONLY") {
    return "Manifest 必须声明 executionMode 为 RESOURCE_ONLY（资源只读，不执行脚本）。";
  }
  if (manifest.length > 8192) {
    return "Manifest 不能超过 8KB。";
  }
  return null;
}

function SkillDialog({
  title,
  hint,
  withNameFields,
  saving,
  error,
  onClose,
  onSubmit,
}: {
  title: string;
  hint: string;
  withNameFields?: boolean;
  saving: boolean;
  error: string | null;
  onClose: () => void;
  onSubmit: (values: { name: string; description: string; manifest: string; packageHash: string }) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [manifest, setManifest] = useState("");
  const [packageHash, setPackageHash] = useState("");

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (!node.open) node.showModal();
    return () => {
      if (node.open) node.close();
    };
  }, []);

  return (
    <dialog ref={ref} className="scene-dialog" aria-labelledby="skill-dialog-title" onCancel={onClose}>
      <form className="model-dialog__form"
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit({ name: name.trim(), description: description.trim(), manifest, packageHash: packageHash.trim() });
        }}
        noValidate>
        <header className="model-dialog__head">
          <div><h2 id="skill-dialog-title">{title}</h2><p>{hint}</p></div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={saving}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          {withNameFields ? (
            <>
              <label className="field">
                <span>名称</span>
                <input value={name} maxLength={200} aria-label="名称" autoFocus
                  onChange={(event) => setName(event.currentTarget.value)} placeholder="例如：规则萃取模板" />
              </label>
              <label className="field">
                <span>描述（可选）</span>
                <input value={description} maxLength={2000} aria-label="描述"
                  onChange={(event) => setDescription(event.currentTarget.value)} placeholder="模板用途与适用边界" />
              </label>
            </>
          ) : null}
          <label className="field">
            <span>Manifest（资源只读 JSON）</span>
            <textarea value={manifest} spellCheck={false} aria-label="Manifest"
              onChange={(event) => setManifest(event.currentTarget.value)}
              placeholder='{"executionMode":"RESOURCE_ONLY","schemaVersion":"1.0","resources":["rules.json"]}' />
            <small>必须声明 executionMode=RESOURCE_ONLY；服务端拒绝脚本、命令、密钥等字段。</small>
          </label>
          <label className="field">
            <span>包哈希（SHA-256）</span>
            <input value={packageHash} maxLength={64} aria-label="包哈希"
              onChange={(event) => setPackageHash(event.currentTarget.value)} placeholder="64 位小写十六进制" />
          </label>
          {error ? <div className="form-error" role="alert">{error}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={saving}>取消</Button>
          <Button type="submit" className="button--primary" disabled={saving}>{saving ? "保存中…" : "保存"}</Button>
        </footer>
      </form>
    </dialog>
  );
}

function ForkInstanceDialog({
  skillName,
  scenes,
  scenesLoading,
  scenesError,
  sceneValue,
  saving,
  error,
  onRetry,
  onSceneChange,
  onClose,
  onSubmit,
}: {
  skillName: string;
  scenes: { id: string; name: string }[];
  scenesLoading: boolean;
  scenesError: boolean;
  sceneValue: string;
  saving: boolean;
  error: string | null;
  onRetry: () => void;
  onSceneChange: (id: string) => void;
  onClose: () => void;
  onSubmit: () => void;
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
    <dialog ref={ref} className="scene-dialog" aria-labelledby="fork-dialog-title" onCancel={onClose}>
      <form className="model-dialog__form"
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit();
        }}
        noValidate>
        <header className="model-dialog__head">
          <div><h2 id="fork-dialog-title">复制为场景实例</h2><p>从模板 {skillName} 派生；实例继承模板名称与最新版本，仅需选择目标场景。</p></div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={saving}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          {scenesLoading ? (
            <div className="model-loading" aria-busy="true">正在加载场景列表…</div>
          ) : scenesError || scenes.length === 0 ? (
            <div className="form-error" role="alert">
              无法加载场景列表（或当前没有可选场景），无法创建实例。
              <Button className="button--quiet button--small" type="button" onClick={onRetry}>重试</Button>
            </div>
          ) : (
            <label className="field">
              <span>目标场景</span>
              <select aria-label="目标场景" value={sceneValue}
                onChange={(event) => onSceneChange(event.currentTarget.value)}>
                {scenes.map((scene) => <option key={scene.id} value={scene.id}>{scene.name}</option>)}
              </select>
            </label>
          )}
          {error ? <div className="form-error" role="alert">{error}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={saving}>取消</Button>
          <Button type="submit" className="button--primary" disabled={saving || scenesLoading || scenesError || scenes.length === 0 || !sceneValue}>
            {saving ? "保存中…" : "创建实例"}
          </Button>
        </footer>
      </form>
    </dialog>
  );
}

export function SkillsPage() {
  const [skills, setSkills] = useState<Skill[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [kindFilter, setKindFilter] = useState<"ALL" | SkillKind>("ALL");
  const [search, setSearch] = useState("");
  const [currentUser, setCurrentUser] = useState<AuthenticatedUser | null>(null);

  const [dialog, setDialog] = useState<
    | { mode: "template" }
    | { mode: "instance"; skill: Skill }
    | { mode: "version"; skill: Skill }
    | null
  >(null);
  const [scenes, setScenes] = useState<Scene[] | null>(null);
  const [scenesLoading, setScenesLoading] = useState(false);
  const [scenesError, setScenesError] = useState(false);
  const [selectedSceneId, setSelectedSceneId] = useState("");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [manifestView, setManifestView] = useState<Skill | null>(null);

  useEffect(() => {
    void getCurrentUser().then(setCurrentUser).catch(() => setCurrentUser(null));
  }, []);

  const loadSkills = useCallback(async (kind: "ALL" | SkillKind) => {
    setLoadError(null);
    try {
      setSkills(await listSkills(kind === "ALL" ? undefined : kind));
    } catch (reason) {
      setSkills(null);
      setLoadError(reason instanceof ApiError ? reason.message : "无法连接 API，请稍后重试。");
    }
  }, []);

  useEffect(() => {
    void loadSkills(kindFilter);
  }, [kindFilter, loadSkills]);

  const visible = useMemo(() => {
    if (skills === null) return [];
    const needle = search.trim().toLowerCase();
    if (!needle) return skills;
    return skills.filter((skill) =>
      skill.name.toLowerCase().includes(needle) || skill.description.toLowerCase().includes(needle));
  }, [skills, search]);

  const isAdmin = Boolean(currentUser?.roles.includes("ADMIN"));
  const canWrite = Boolean(currentUser && (currentUser.roles.includes("ADMIN") || currentUser.roles.includes("OPERATOR")));

  const openInstanceDialog = async (skill: Skill) => {
    setFormError(null);
    setScenes(null);
    setScenesLoading(true);
    setScenesError(false);
    setDialog({ mode: "instance", skill });
    try {
      const page = await listScenes(0, 50);
      setScenes(page.items);
      setSelectedSceneId(page.items[0]?.id ?? "");
    } catch {
      setScenesError(true);
      setScenes([]);
    } finally {
      setScenesLoading(false);
    }
  };

  const submit = async (values: { name: string; description: string; manifest: string; packageHash: string }) => {
    if (!dialog || dialog.mode === "instance") return;
    const manifestError = validateManifestInput(values.manifest);
    if (manifestError) {
      setFormError(manifestError);
      return;
    }
    if (!/^[0-9a-f]{64}$/.test(values.packageHash)) {
      setFormError("包哈希必须是 64 位小写十六进制。");
      return;
    }
    if (dialog.mode === "template" && !values.name) {
      setFormError("请输入模板名称。");
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      if (dialog.mode === "template") {
        await createSkill({
          name: values.name,
          description: values.description || undefined,
          manifest: values.manifest,
          packageHash: values.packageHash,
        }, crypto.randomUUID());
      } else {
        await createSkillVersion(dialog.skill.id, values.manifest, values.packageHash, crypto.randomUUID());
      }
      setDialog(null);
      await loadSkills(kindFilter);
    } catch (reason) {
      setFormError(reason instanceof ApiError ? reason.message : "保存失败，请稍后重试。");
    } finally {
      setSaving(false);
    }
  };

  const submitFork = async () => {
    if (!dialog || dialog.mode !== "instance") return;
    if (!selectedSceneId) {
      setFormError("请选择目标场景。");
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      await forkSkillInstance(dialog.skill.id, selectedSceneId, crypto.randomUUID());
      setDialog(null);
      await loadSkills(kindFilter);
    } catch (reason) {
      setFormError(reason instanceof ApiError ? reason.message : "创建实例失败，请稍后重试。");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="page">
      <PageHeader
        eyebrow="治理 / Skill 库"
        title="模板是起点，版本才是交付物"
        description="Skill 支持只读资源包（RESOURCE_ONLY）和受限声明式规则（SANDBOX_V1）；不执行上传的源代码。仅 ADMIN 可建模板，OPERATOR/ADMIN 可建实例与版本。"
        actions={isAdmin ? <Button className="button--primary" onClick={() => setDialog({ mode: "template" })}><Glyph name="plus" />新建模板</Button> : null}
      />
      <div className="security-note"><Glyph name="lock" size={17} /><div><b>安全边界</b><p>不执行 Skill 包内的 Shell、Python、JavaScript、WASM、二进制或校验脚本；SANDBOX_V1 仅运行经校验的声明式分类规则。</p></div><Status tone="success">受限声明式模式</Status></div>
      {loadError ? (
        <div className="load-error" role="alert">
          <Glyph name="warning" size={16} />
          <div><b>无法加载 Skill 列表</b><span>{loadError}</span></div>
          <Button className="button--quiet button--small" onClick={() => void loadSkills(kindFilter)}>重试</Button>
        </div>
      ) : null}
      <div className="section-head section-head--compact">
        <div className="segmented" aria-label="过滤 Skill 类型">
          {(["ALL", "TEMPLATE", "INSTANCE"] as const).map((item) => (
            <button key={item} className={kindFilter === item ? "active" : ""}
              onClick={() => setKindFilter(item)}>{{ ALL: "全部", TEMPLATE: "通用模板", INSTANCE: "场景实例" }[item]}</button>
          ))}
        </div>
        <label className="inline-search"><Glyph name="search" size={15} /><input placeholder="搜索 Skill 名称或描述" value={search}
          onChange={(event) => setSearch(event.currentTarget.value)} /></label>
      </div>
      {skills === null && !loadError ? (
        <div className="model-loading" aria-busy="true">正在加载 Skill 列表…</div>
      ) : null}
      {skills !== null && visible.length === 0 ? (
        <EmptyState title={skills.length === 0 ? "还没有 Skill" : "没有匹配的 Skill"}
          detail={skills.length === 0 ? "管理员可以创建第一个通用模板。" : "调整搜索或类型过滤。"} />
      ) : null}
      {skills !== null && visible.length > 0 ? (
        <div className="skill-grid">
          {visible.map((skill) => (
            <article className="skill-card" key={skill.id}>
              <header><span className="skill-glyph"><Glyph name="skill" size={19} /></span>
                <Status tone={skill.kind === "TEMPLATE" ? "purple" : "info"}>{skill.kind === "TEMPLATE" ? "通用模板" : "场景实例"}</Status></header>
              <h3>{skill.name} <code>{skill.version ? `v${skill.version}` : "—"}</code></h3>
              <p>{skill.description || "暂无描述"}</p>
              <dl>
                {skill.kind === "INSTANCE" && skill.sourceSkillId ? <div><dt>Fork 自</dt><dd><code>{shortId(skill.sourceSkillId)}</code></dd></div> : null}
                {skill.kind === "INSTANCE" && skill.sceneId ? <div><dt>适用场景</dt><dd><code>{shortId(skill.sceneId)}</code></dd></div> : null}
                <div><dt>包哈希</dt><dd><code>{skill.packageHash ? skill.packageHash.slice(0, 12) + "…" : "—"}</code></dd></div>
              </dl>
              <footer>
                <button onClick={() => setManifestView(skill)}>查看 Manifest</button>
                {skill.kind === "TEMPLATE" && canWrite ? (
                  <button className="skill-card__primary" onClick={() => void openInstanceDialog(skill)}>复制为实例</button>
                ) : skill.kind === "INSTANCE" && canWrite ? (
                  <button className="skill-card__primary" onClick={() => setDialog({ mode: "version", skill })}>创建新版本</button>
                ) : null}
              </footer>
            </article>
          ))}
        </div>
      ) : null}
      {!canWrite ? (
        <div className="release-panel__body-note" role="note"><Glyph name="lock" size={14} /> 当前账号不可写：创建实例与版本需要 OPERATOR 或 ADMIN；创建模板需要 ADMIN。</div>
      ) : null}

      {manifestView ? (
        <dialog className="scene-dialog" aria-labelledby="manifest-dialog-title" open>
          <div className="model-dialog__form">
            <header className="model-dialog__head">
              <div><h2 id="manifest-dialog-title">Manifest · {manifestView.name} v{manifestView.version ?? "—"}</h2><p>只读展示；仅 SANDBOX_V1 声明式规则可由隔离评测进程解释，源代码永不执行。</p></div>
              <button type="button" className="icon-button" aria-label="关闭" onClick={() => setManifestView(null)}><Glyph name="close" size={16} /></button>
            </header>
            <div className="model-dialog__body">
              <pre className="manifest-view">{manifestView.manifestJson ?? "（无 Manifest）"}</pre>
            </div>
          </div>
        </dialog>
      ) : null}

      {dialog?.mode === "template" ? (
        <SkillDialog title="新建通用模板" hint="仅 ADMIN；Manifest 只允许 RESOURCE_ONLY 或 SANDBOX_V1 声明式规则。" withNameFields saving={saving} error={formError}
          onClose={() => setDialog(null)} onSubmit={(values) => void submit(values)} />
      ) : null}
      {dialog?.mode === "instance" ? (
        <ForkInstanceDialog skillName={dialog.skill.name}
          scenes={scenes?.map((scene) => ({ id: scene.id, name: scene.name })) ?? []}
          scenesLoading={scenesLoading} scenesError={scenesError}
          sceneValue={selectedSceneId} onSceneChange={setSelectedSceneId}
          saving={saving} error={formError}
          onRetry={() => void openInstanceDialog(dialog.skill)}
          onClose={() => setDialog(null)} onSubmit={() => void submitFork()} />
      ) : null}
      {dialog?.mode === "version" ? (
        <SkillDialog title={`创建新版本 · ${dialog.skill.name}`} hint="版本不可变，追加后无法修改。" saving={saving} error={formError}
          onClose={() => setDialog(null)} onSubmit={(values) => void submit(values)} />
      ) : null}
    </div>
  );
}
