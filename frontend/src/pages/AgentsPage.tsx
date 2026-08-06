import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, EmptyState, Glyph, PageHeader, Status } from "../components/Ui";
import {
  ApiError,
  appendAgentMount,
  applyConfigurationImport,
  getAgentConfigurationCatalog,
  getAgentScope,
  getCurrentUser,
  getEffectiveAgentConfigurations,
  listAgentRoles,
  listScenes,
  listSubScenes,
  previewConfigurationImport,
} from "../lib/api";
import type {
  AgentConfigurationCatalog,
  AgentMountDraft,
  AgentMountScope,
  AgentRole,
  AgentRoleDefinition,
  AgentScopeConfiguration,
  ConfigurationImportPreview,
  EffectiveAgentConfiguration,
  Scene,
  SubScene,
} from "../lib/api";

type Editor = { enabled: "" | "true" | "false"; model: string; skill: string; options: string };

const STAGE_NOTES: Record<string, string> = {
  "环节一": "定义场景与素材边界",
  "环节二": "萃取、冲突检测与对齐",
  "环节三": "从定稿 Revision 生成交付资产",
};

const TRIGGERS: Record<AgentRole, string> = {
  SCENE_EXPLORER: "探索候选场景",
  KNOWLEDGE_EXTRACTOR: "提交萃取任务",
  ALIGNMENT_REVIEWER: "创建对齐提案",
  RULE_CATALOG_GENERATOR: "生成规则清单",
  DECISION_FLOW_GENERATOR: "生成研判流程",
  SKILL_PACKAGER: "生成 Skill 包",
  QA_EVALUATOR: "生成 QA / 评测集",
};

const BUILT_IN_SKILLS: Record<AgentRole, string> = {
  SCENE_EXPLORER: "场景探索基础模板",
  KNOWLEDGE_EXTRACTOR: "知识萃取基础模板",
  ALIGNMENT_REVIEWER: "冲突检测与对齐基础模板",
  RULE_CATALOG_GENERATOR: "规则库生成基础模板",
  DECISION_FLOW_GENERATOR: "研判流程生成基础模板",
  SKILL_PACKAGER: "Skill 打包基础模板",
  QA_EVALUATOR: "QA 与评测基础模板",
};

const SCOPE_LABELS: Record<AgentMountScope, string> = {
  GLOBAL: "全局模板",
  SCENE: "场景默认",
  SUB_SCENE: "子场景覆盖",
};

function shortHash(value: string | null | undefined): string {
  return value ? `${value.slice(0, 8)}…${value.slice(-4)}` : "—";
}

function editorFrom(scope: AgentScopeConfiguration, role: AgentRole): Editor {
  const mount = scope.mounts.find((item) => item.role === role);
  return {
    enabled: mount?.enabled === true ? "true" : mount?.enabled === false ? "false" : "",
    model: mount?.modelConfigVersionId ?? "",
    skill: mount?.skillVersionId ?? "",
    options: mount?.optionsJson ?? "",
  };
}

function sourceLabel(source: AgentMountScope | "TEMPLATE" | null): string {
  if (!source) return "未配置";
  if (source === "TEMPLATE") return "角色模板";
  return SCOPE_LABELS[source];
}

export function AgentsPage() {
  const [roles, setRoles] = useState<AgentRoleDefinition[]>([]);
  const [catalog, setCatalog] = useState<AgentConfigurationCatalog>({ models: [], skills: [] });
  const [scenes, setScenes] = useState<Scene[]>([]);
  const [subScenes, setSubScenes] = useState<SubScene[]>([]);
  const [subScenesLoadedForSceneId, setSubScenesLoadedForSceneId] = useState("");
  const [sceneId, setSceneId] = useState("");
  const [subSceneId, setSubSceneId] = useState("");
  const [scopeType, setScopeType] = useState<AgentMountScope>("SCENE");
  const [isAdmin, setIsAdmin] = useState(false);
  const [scope, setScope] = useState<AgentScopeConfiguration | null>(null);
  const [effective, setEffective] = useState<EffectiveAgentConfiguration[]>([]);
  const [editors, setEditors] = useState<Partial<Record<AgentRole, Editor>>>({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [savingRole, setSavingRole] = useState<AgentRole | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [importText, setImportText] = useState("");
  const [importPreview, setImportPreview] = useState<ConfigurationImportPreview | null>(null);
  const [importBusy, setImportBusy] = useState(false);
  const configurationLoadSequence = useRef(0);

  useEffect(() => {
    let active = true;
    void Promise.all([listAgentRoles(), getAgentConfigurationCatalog(), listScenes(0, 100), getCurrentUser()])
      .then(([roleItems, catalogItems, scenePage, user]) => {
        if (!active) return;
        setRoles(roleItems);
        setCatalog(catalogItems);
        setScenes(scenePage.items);
        setSceneId(scenePage.items[0]?.id ?? "");
        setIsAdmin(user.roles.includes("ADMIN"));
      })
      .catch((reason) => {
        if (active) setLoadError(reason instanceof ApiError ? reason.message : "无法读取智能体治理数据。");
      });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!sceneId) {
      setSubScenes([]);
      setSubSceneId("");
      setSubScenesLoadedForSceneId("");
      return;
    }
    let active = true;
    setLoading(true);
    setSubScenesLoadedForSceneId("");
    void listSubScenes(sceneId).then((items) => {
      if (!active) return;
      setSubScenes(items);
      setSubSceneId((current) => items.some((item) => item.id === current) ? current : items[0]?.id ?? "");
      setSubScenesLoadedForSceneId(sceneId);
    }).catch((reason) => {
      if (active) {
        setLoadError(reason instanceof ApiError ? reason.message : "无法读取子场景。");
        setSubScenesLoadedForSceneId(sceneId);
      }
    });
    return () => { active = false; };
  }, [sceneId]);

  const scopeId = scopeType === "GLOBAL" ? null : scopeType === "SCENE" ? sceneId : subSceneId || null;

  const reloadConfiguration = useCallback(async () => {
    if (!sceneId || subScenesLoadedForSceneId !== sceneId || (scopeType === "SUB_SCENE" && !subSceneId)) return;
    const sequence = ++configurationLoadSequence.current;
    setLoading(true);
    setLoadError(null);
    try {
      const [scopeResult, effectiveResult] = await Promise.all([
        getAgentScope(scopeType, scopeId),
        getEffectiveAgentConfigurations(sceneId, subSceneId || null),
      ]);
      if (sequence !== configurationLoadSequence.current) return;
      setScope(scopeResult);
      setEffective(effectiveResult);
      setEditors(Object.fromEntries(roles.map((role) => [role.role, editorFrom(scopeResult, role.role)])));
    } catch (reason) {
      if (sequence === configurationLoadSequence.current) {
        setLoadError(reason instanceof ApiError ? reason.message : "无法解析配置继承关系。");
      }
    } finally {
      if (sequence === configurationLoadSequence.current) setLoading(false);
    }
  }, [roles, sceneId, subSceneId, subScenesLoadedForSceneId, scopeType, scopeId]);

  useEffect(() => { void reloadConfiguration(); }, [reloadConfiguration]);

  const modelById = useMemo(() => new Map(catalog.models.map((item) => [item.versionId, item])), [catalog]);
  const skillById = useMemo(() => new Map(catalog.skills.map((item) => [item.versionId, item])), [catalog]);
  const availableSkills = useMemo(() => catalog.skills.filter((item) =>
    item.kind === "TEMPLATE" || (scopeType !== "GLOBAL" && item.sceneId === sceneId)), [catalog, scopeType, sceneId]);

  const updateEditor = (role: AgentRole, patch: Partial<Editor>) => {
    setEditors((value) => ({ ...value, [role]: { ...(value[role] ?? { enabled: "", model: "", skill: "", options: "" }), ...patch } }));
  };

  const draftFor = (role: AgentRole): AgentMountDraft => {
    const editor = editors[role] ?? { enabled: "", model: "", skill: "", options: "" };
    let options: Record<string, unknown> | null = null;
    if (editor.options.trim()) {
      const parsed = JSON.parse(editor.options) as unknown;
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("运行参数必须是 JSON 对象。");
      options = parsed as Record<string, unknown>;
    }
    return {
      role,
      enabled: editor.enabled === "" ? null : editor.enabled === "true",
      modelConfigVersionId: editor.model || null,
      skillVersionId: editor.skill || null,
      options,
    };
  };

  const saveRole = async (role: AgentRole) => {
    if (!scope || savingRole) return;
    setActionError(null);
    setNotice(null);
    let draft: AgentMountDraft;
    try {
      draft = draftFor(role);
      if (draft.enabled === null && !draft.modelConfigVersionId && !draft.skillVersionId && draft.options === null) {
        setActionError("当前层没有任何覆盖项；至少选择一个字段后再保存。");
        return;
      }
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : "运行参数不是有效 JSON。");
      return;
    }
    setSavingRole(role);
    try {
      await appendAgentMount(scopeType, scopeId, scope.etag, draft);
      setNotice(`${roles.find((item) => item.role === role)?.displayName ?? role} 已追加新版本。`);
      await reloadConfiguration();
    } catch (reason) {
      setActionError(reason instanceof ApiError ? reason.message : "保存失败，请刷新后重试。");
    } finally {
      setSavingRole(null);
    }
  };

  const enableRole = async (role: AgentRole) => {
    if (!scope || savingRole) return;
    setActionError(null);
    setNotice(null);
    const resolved = effective.find((item) => item.role === role);
    let draft: AgentMountDraft;
    try {
      draft = draftFor(role);
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : "运行参数不是有效 JSON。");
      return;
    }
    const fallbackModel = catalog.models[0];
    const expectedSkillName = BUILT_IN_SKILLS[role];
    const fallbackSkill = availableSkills.find((item) => item.name === expectedSkillName && item.kind === "INSTANCE")
      ?? availableSkills.find((item) => item.name === expectedSkillName && item.kind === "TEMPLATE");
    if (!draft.modelConfigVersionId && !resolved?.modelConfigVersionId && !fallbackModel) {
      setActionError("没有可用的模型配置。请先在“模型”页面创建并启用一个模型连接。");
      return;
    }
    if (!draft.skillVersionId && !resolved?.skillVersionId && !fallbackSkill) {
      setActionError(`缺少“${expectedSkillName}”。请刷新页面；若仍未出现，请确认服务已完成内置 Skill 初始化。`);
      return;
    }
    draft = {
      ...draft,
      enabled: true,
      modelConfigVersionId: draft.modelConfigVersionId
        ?? (resolved?.modelConfigVersionId ? null : fallbackModel?.versionId ?? null),
      skillVersionId: draft.skillVersionId
        ?? (resolved?.skillVersionId ? null : fallbackSkill?.versionId ?? null),
    };
    setSavingRole(role);
    try {
      await appendAgentMount(scopeType, scopeId, scope.etag, draft);
      setNotice(`${roles.find((item) => item.role === role)?.displayName ?? role} 已启用，模型与 Skill 版本已固化。`);
      await reloadConfiguration();
    } catch (reason) {
      setActionError(reason instanceof ApiError ? reason.message : "启用失败，请刷新后重试。");
    } finally {
      setSavingRole(null);
    }
  };

  const parseImport = (): AgentMountDraft[] => {
    const parsed = JSON.parse(importText) as unknown;
    const values = Array.isArray(parsed)
      ? parsed
      : parsed && typeof parsed === "object" && Array.isArray((parsed as { roles?: unknown }).roles)
        ? (parsed as { roles: unknown[] }).roles
        : null;
    if (!values) throw new Error("导入内容必须是角色配置数组，或包含 roles 数组的对象。");
    return values.map((item) => {
      if (!item || typeof item !== "object") throw new Error("每个角色配置必须是 JSON 对象。");
      const value = item as Partial<AgentMountDraft>;
      if (!roles.some((role) => role.role === value.role)) throw new Error(`未知 AgentRole：${String(value.role)}`);
      return {
        role: value.role as AgentRole,
        enabled: typeof value.enabled === "boolean" ? value.enabled : null,
        modelConfigVersionId: typeof value.modelConfigVersionId === "string" ? value.modelConfigVersionId : null,
        skillVersionId: typeof value.skillVersionId === "string" ? value.skillVersionId : null,
        options: value.options && typeof value.options === "object" && !Array.isArray(value.options)
          ? value.options : null,
      };
    });
  };

  const previewImport = async () => {
    setActionError(null);
    setImportPreview(null);
    setImportBusy(true);
    try {
      setImportPreview(await previewConfigurationImport(scopeType, scopeId, parseImport()));
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : "导入预览失败。");
    } finally {
      setImportBusy(false);
    }
  };

  const applyImport = async () => {
    if (!importPreview) return;
    setImportBusy(true);
    setActionError(null);
    try {
      await applyConfigurationImport(importPreview.id, importPreview.manifestHash);
      setNotice("配置导入已作为一组不可变版本应用。");
      setImportOpen(false);
      setImportPreview(null);
      setImportText("");
      await reloadConfiguration();
    } catch (reason) {
      setActionError(reason instanceof ApiError ? reason.message : "应用配置导入失败。");
    } finally {
      setImportBusy(false);
    }
  };

  const stages = ["环节一", "环节二", "环节三"];

  return (
    <div className="page agent-governance">
      <PageHeader eyebrow="治理 / 智能体挂载" title="智能体角色与挂载"
        description="按场景和子场景配置七种智能体角色。每次修改都会产生不可变版本，可随发布记录追溯。"
        actions={<Button className="button--quiet" onClick={() => { setImportOpen((value) => !value); setActionError(null); }}>
          <Glyph name="download" size={14} />导入配置
        </Button>} />

      <section className="agent-lineage-panel" aria-label="配置继承层级">
        {(["GLOBAL", "SCENE", "SUB_SCENE"] as AgentMountScope[]).map((item, index) => {
          const disabled = (item === "GLOBAL" && !isAdmin) || (item === "SUB_SCENE" && !subSceneId);
          return <button key={item} type="button" className={scopeType === item ? "is-active" : ""}
            disabled={disabled} onClick={() => setScopeType(item)}>
            <span>{index + 1}</span><b>{SCOPE_LABELS[item]}</b>
            <small>{item === "GLOBAL" ? "ADMIN 管理" : item === "SCENE" ? "场景内复用" : "最高优先级"}</small>
          </button>;
        })}
      </section>

      <section className="scope-bar">
        <div><span className="scope-bar__mark"><Glyph name="scene" size={17} /></span><div><b>当前编辑层：{SCOPE_LABELS[scopeType]}</b><small>ETag {shortHash(scope?.etag)}</small></div></div>
        <label><span>主场景</span><select value={sceneId} onChange={(event) => setSceneId(event.target.value)}>
          {scenes.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
        </select></label>
        <Glyph name="chevron" size={14} />
        <label><span>子场景</span><select value={subSceneId} onChange={(event) => setSubSceneId(event.target.value)}>
          {subScenes.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
        </select></label>
        <Status tone={scope?.mounts.length ? "info" : "neutral"}>{scope?.mounts.length ?? 0} 个本层版本头</Status>
      </section>

      {importOpen ? <section className="agent-import-panel">
        <header><div><b>配置导入</b><small>上传 → 校验 → Diff 预览 → 事务应用</small></div><button className="icon-button" onClick={() => setImportOpen(false)} aria-label="关闭导入"><Glyph name="close" size={15} /></button></header>
        <textarea value={importText} onChange={(event) => setImportText(event.target.value)}
          placeholder={'[{"role":"KNOWLEDGE_EXTRACTOR","enabled":true,"modelConfigVersionId":"…","skillVersionId":"…","options":{"strategy":"balanced"}}]'} />
        {importPreview ? <div className="agent-import-diff"><b>校验通过 · Manifest {shortHash(importPreview.manifestHash)}</b><pre>{JSON.stringify(JSON.parse(importPreview.diffJson), null, 2)}</pre></div> : null}
        <footer><Button className="button--quiet" onClick={() => void previewImport()} disabled={importBusy || !importText.trim()}>{importBusy ? "校验中…" : "生成 Diff 预览"}</Button>
          <Button className="button--primary" onClick={() => void applyImport()} disabled={importBusy || !importPreview}>确认事务应用</Button></footer>
      </section> : null}

      {notice ? <div className="agent-feedback agent-feedback--success" role="status"><Glyph name="check" size={15} />{notice}</div> : null}
      {actionError ? <div className="agent-feedback agent-feedback--error" role="alert"><Glyph name="warning" size={15} />{actionError}</div> : null}
      {loadError ? <div className="agent-feedback agent-feedback--error" role="alert"><Glyph name="warning" size={15} />{loadError}<button onClick={() => void reloadConfiguration()}>重试</button></div> : null}

      {!loading && scenes.length === 0 ? <EmptyState title="尚无场景" detail="先在工作台创建场景与子场景，再配置 Agent 挂载。" /> : null}
      <div className="stage-groups" aria-busy={loading}>
        {stages.map((stage) => (
          <section key={stage} className="stage-group">
            <header><span>{stage}</span><div className="stage-group__line" /><small>{STAGE_NOTES[stage]}</small></header>
            <div className="agent-grid">
              {roles.filter((role) => role.stage === stage).map((role) => {
                const resolved = effective.find((item) => item.role === role.role);
                const editor = editors[role.role] ?? { enabled: "", model: "", skill: "", options: "" };
                const configured = Boolean(resolved?.enabled && resolved.modelConfigVersionId && resolved.skillVersionId);
                const model = resolved?.modelConfigVersionId ? modelById.get(resolved.modelConfigVersionId) : null;
                const skill = resolved?.skillVersionId ? skillById.get(resolved.skillVersionId) : null;
                const mount = scope?.mounts.find((item) => item.role === role.role);
                return <article className={`agent-card ${resolved?.enabled ? "" : "agent-card--disabled"}`} key={role.role}>
                  <div className="agent-card__head"><span className="agent-index">{String(roles.indexOf(role) + 1).padStart(2, "0")}</span><div><h3>{role.displayName}</h3><span>触发：{TRIGGERS[role.role]}</span></div><Status tone={configured ? "success" : resolved?.enabled ? "warning" : "neutral"}>{configured ? "已就绪" : resolved?.enabled ? "待补齐" : "未启用"}</Status></div>
                  <p>{role.description}</p>
                  <div className="agent-source-strip" aria-label="有效配置来源">
                    <span><i>启停</i><b>{sourceLabel(resolved?.enabledSource ?? null)}</b></span>
                    <span><i>模型</i><b>{sourceLabel(resolved?.modelSource ?? null)}</b></span>
                    <span><i>Skill</i><b>{sourceLabel(resolved?.skillSource ?? null)}</b></span>
                    <span><i>参数</i><b>{sourceLabel(resolved?.optionsSource ?? null)}</b></span>
                  </div>
                  <div className="agent-effective-summary">
                    <span>有效模型<b>{model ? `${model.connectionName} / ${model.modelId} · v${model.version}` : "未配置"}</b></span>
                    <span>有效 Skill<b>{skill ? `${skill.name} · v${skill.version}` : "未配置"}</b></span>
                  </div>
                  <div className="agent-config">
                    <label><span>本层启停</span><select value={editor.enabled} disabled={loading} onChange={(event) => updateEditor(role.role, { enabled: event.target.value as Editor["enabled"] })}>
                      <option value="">继承上级</option><option value="true">启用</option><option value="false">停用</option>
                    </select></label>
                    <div><label><span>本层模型版本</span><select value={editor.model} disabled={loading} onChange={(event) => updateEditor(role.role, { model: event.target.value })}>
                      <option value="">继承上级</option>{catalog.models.map((item) => <option key={item.versionId} value={item.versionId}>{item.connectionName} / {item.modelId} · v{item.version}</option>)}
                    </select></label><label><span>本层 Skill 版本</span><select value={editor.skill} disabled={loading} onChange={(event) => updateEditor(role.role, { skill: event.target.value })}>
                      <option value="">继承上级</option>{availableSkills.map((item) => <option key={item.versionId} value={item.versionId}>{item.name} · v{item.version}</option>)}
                    </select></label></div>
                    <label><span>本层运行参数（JSON 对象，留空继承）</span><input value={editor.options} disabled={loading} onChange={(event) => updateEditor(role.role, { options: event.target.value })} placeholder='{"strategy":"balanced"}' /></label>
                  </div>
                  <footer><span><Glyph name="lock" size={13} />{mount ? `本层 v${mount.version} · ${shortHash(mount.configHash)}` : `有效 ${shortHash(resolved?.effectiveHash)}`}</span><div className="agent-card__actions">
                    <Button className="button--quiet" onClick={() => void saveRole(role.role)} disabled={loading || Boolean(savingRole)}>{savingRole === role.role ? "保存中…" : "保存配置版本"}</Button>
                    {!resolved?.enabled ? <Button className="agent-enable-button" onClick={() => void enableRole(role.role)} disabled={loading || Boolean(savingRole)}><Glyph name="play" size={13} />{savingRole === role.role ? "启用中…" : "启用智能体"}</Button> : null}
                  </div></footer>
                </article>;
              })}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
