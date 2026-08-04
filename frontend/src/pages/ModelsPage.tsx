import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { Button, EmptyState, Glyph, PageHeader, Status } from "../components/Ui";
import {
  ApiError,
  createModelConfigVersion,
  createModelConnection,
  deleteModelConnection,
  listModelConfigVersions,
  listModelConnections,
  testModelConnection,
  updateModelConnection,
} from "../lib/api";
import type {
  ModelConfigVersion,
  ModelConfigVersionDraft,
  ModelConnection,
  ModelConnectionDraft,
  ModelConnectionTestResult,
  ModelProvider,
} from "../lib/api";
import { connectionTestSummary, connectionValidationLabel, modelProviderLabel, toStatusTone } from "../domain";

type ConnectionDialogState = { mode: "create" } | { mode: "edit"; connection: ModelConnection };

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

function TestResultNote({ result }: { result: ModelConnectionTestResult }) {
  const summary = connectionTestSummary(result);
  return (
    <div className={`model-inline-note${result.connectivityVerified ? "" : " model-inline-note--error"}`} role="status">
      <Glyph name={result.connectivityVerified ? "check" : "warning"} size={15} />
      <div>
        <b>{summary.label}</b>
        <span>{summary.note} · {formatDateTime(result.testedAt)}</span>
      </div>
      <code>{result.messageCode}</code>
    </div>
  );
}

function ConnectionDialog({
  dialog,
  saving,
  formError,
  formFieldErrors,
  onClose,
  onSubmit,
}: {
  dialog: ConnectionDialogState;
  saving: boolean;
  formError: string | null;
  formFieldErrors: Record<string, string>;
  onClose: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const editing = dialog.mode === "edit" ? dialog.connection : null;

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (!node.open) node.showModal();
    return () => {
      if (node.open) node.close();
    };
  }, []);

  return (
    <dialog ref={ref} className="model-dialog" aria-labelledby="model-dialog-title" onCancel={onClose}>
      <form className="model-dialog__form" onSubmit={onSubmit} noValidate>
        <header className="model-dialog__head">
          <div>
            <h2 id="model-dialog-title">{editing ? "编辑模型连接" : "新增模型连接"}</h2>
            <p>{editing ? "凭据为只写字段，本页永远不会回显已保存的密钥。" : "凭据只发送到同源 API，不写入浏览器存储。"}</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={saving}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          <label className="field">
            <span>名称</span>
            <input name="name" autoFocus autoComplete="off" defaultValue={editing?.name} maxLength={200}
              placeholder="例如：企业模型网关" aria-invalid={Boolean(formFieldErrors.name)} />
            {formFieldErrors.name ? <small className="field-error">{formFieldErrors.name}</small> : null}
          </label>
          <label className="field">
            <span>Provider</span>
            <select name="provider" defaultValue={editing?.provider ?? "OPENAI_COMPATIBLE"}
              aria-invalid={Boolean(formFieldErrors.provider)}>
              <option value="OPENAI_COMPATIBLE">OpenAI 兼容</option>
              <option value="DASHSCOPE">DashScope</option>
            </select>
            {formFieldErrors.provider ? <small className="field-error">{formFieldErrors.provider}</small> : null}
          </label>
          <label className="field">
            <span>Base URL</span>
            <input name="baseUrl" autoComplete="off" defaultValue={editing?.baseUrl} maxLength={2048}
              placeholder="https://…" aria-invalid={Boolean(formFieldErrors.baseUrl)} />
            <small>必须以 https:// 开头，且命中管理员维护的主机白名单。</small>
            {formFieldErrors.baseUrl ? <small className="field-error">{formFieldErrors.baseUrl}</small> : null}
          </label>
          <label className="field">
            <span>凭据（只写）</span>
            <input name="credential" type="password" autoComplete="new-password"
              placeholder={editing ? "留空保持不变" : "API Key，仅本次写入"} maxLength={8192}
              aria-invalid={Boolean(formFieldErrors.credential)} />
            {editing
              ? <small>留空表示保持现有凭据不变；不会读取或回显已保存的密钥。</small>
              : <small>密钥将使用服务端密钥信封加密后持久化。</small>}
            {formFieldErrors.credential ? <small className="field-error">{formFieldErrors.credential}</small> : null}
          </label>
          {editing ? (
            <label className="field field--row">
              <span>清除已配置凭据</span>
              <input type="checkbox" name="clearCredential" />
            </label>
          ) : null}
          <div className="field field--row">
            <span>启用该连接</span>
            <label className="switch">
              <input type="checkbox" name="enabled" defaultChecked={editing ? editing.enabled : true} />
              <span />
            </label>
          </div>
          {formError ? <div className="form-error" role="alert">{formError}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={saving}>取消</Button>
          <Button type="submit" className="button--primary" disabled={saving}>
            {saving ? "保存中…" : editing ? "保存修改" : "创建连接"}
          </Button>
        </footer>
      </form>
    </dialog>
  );
}

export function ModelsPage() {
  const [connections, setConnections] = useState<ModelConnection[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [successNotice, setSuccessNotice] = useState<string | null>(null);

  const [dialog, setDialog] = useState<ConnectionDialogState | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [formFieldErrors, setFormFieldErrors] = useState<Record<string, string>>({});
  const dialogTrigger = useRef<HTMLElement | null>(null);

  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResults, setTestResults] = useState<Record<string, ModelConnectionTestResult>>({});
  const [testErrors, setTestErrors] = useState<Record<string, string>>({});

  const [versionsFor, setVersionsFor] = useState<string | null>(null);
  const [versions, setVersions] = useState<ModelConfigVersion[] | null>(null);
  const [versionsError, setVersionsError] = useState<string | null>(null);
  const [versionSaving, setVersionSaving] = useState(false);
  const [versionFormError, setVersionFormError] = useState<string | null>(null);

  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const loadConnections = useCallback(async () => {
    setLoadError(null);
    try {
      setConnections(await listModelConnections());
    } catch (reason) {
      setConnections(null);
      setLoadError(reason instanceof ApiError ? reason.message : "无法连接 API，请确认已登录后重试。");
    }
  }, []);

  useEffect(() => {
    void loadConnections();
  }, [loadConnections]);

  const openDialog = (state: ConnectionDialogState) => {
    dialogTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setFormError(null);
    setFormFieldErrors({});
    setDialog(state);
  };

  const closeDialog = () => {
    setDialog(null);
    dialogTrigger.current?.focus();
  };

  const submitConnection = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving || !dialog) return;
    setFormError(null);
    setFormFieldErrors({});
    const form = new FormData(event.currentTarget);
    const name = String(form.get("name") ?? "").trim();
    const provider = String(form.get("provider") ?? "") as ModelProvider;
    const baseUrl = String(form.get("baseUrl") ?? "").trim();
    const credential = String(form.get("credential") ?? "");
    const enabled = form.get("enabled") === "on";
    if (!name) {
      setFormError("请输入连接名称。");
      return;
    }
    if (!/^https:\/\/.+/.test(baseUrl)) {
      setFormError("Base URL 必须是以 https:// 开头的完整地址。");
      return;
    }
    const clearCredential = dialog.mode === "edit" && form.get("clearCredential") === "on";
    if (clearCredential && credential.trim()) {
      setFormError("清除凭据与输入新凭据不能同时进行。");
      return;
    }
    setSaving(true);
    try {
      const draft: ModelConnectionDraft = { name, provider, baseUrl, enabled };
      if (!clearCredential && credential.trim()) draft.credential = credential;
      if (dialog.mode === "edit") {
        await updateModelConnection(dialog.connection.id, { ...draft, clearCredential });
      } else {
        await createModelConnection(draft);
      }
      closeDialog();
      setSuccessNotice(dialog.mode === "edit" ? "连接已更新，列表已刷新。" : "连接已创建，列表已刷新。");
      await loadConnections();
    } catch (reason) {
      if (reason instanceof ApiError) {
        setFormError(reason.message);
        setFormFieldErrors(fieldErrorsToRecord(reason.errors));
      } else {
        setFormError("保存失败，请稍后重试。");
      }
    } finally {
      setSaving(false);
    }
  };

  const runTest = async (connection: ModelConnection) => {
    if (testingId !== null) return;
    setTestingId(connection.id);
    setTestErrors((errors) => {
      const next = { ...errors };
      delete next[connection.id];
      return next;
    });
    try {
      const result = await testModelConnection(connection.id);
      setTestResults((results) => ({ ...results, [connection.id]: result }));
      await loadConnections();
    } catch (reason) {
      setTestErrors((errors) => ({
        ...errors,
        [connection.id]: reason instanceof ApiError ? reason.message : "连接测试失败，请稍后重试。",
      }));
    } finally {
      setTestingId(null);
    }
  };

  const toggleVersions = async (connectionId: string) => {
    if (versionsFor === connectionId) {
      setVersionsFor(null);
      setVersions(null);
      setVersionsError(null);
      return;
    }
    setVersionsFor(connectionId);
    setVersions(null);
    setVersionsError(null);
    try {
      setVersions(await listModelConfigVersions(connectionId));
    } catch (reason) {
      setVersionsError(reason instanceof ApiError ? reason.message : "无法读取配置版本。");
    }
  };

  const submitVersion = async (event: FormEvent<HTMLFormElement>, connectionId: string) => {
    event.preventDefault();
    if (versionSaving) return;
    const form = event.currentTarget;
    setVersionFormError(null);
    const formData = new FormData(form);
    const modelId = String(formData.get("modelId") ?? "").trim();
    const temperatureRaw = String(formData.get("temperature") ?? "").trim();
    const maxOutputTokensRaw = String(formData.get("maxOutputTokens") ?? "").trim();
    const temperature = Number(temperatureRaw);
    const maxOutputTokens = Number(maxOutputTokensRaw);
    if (!modelId) {
      setVersionFormError("请输入 Model ID。");
      return;
    }
    if (temperatureRaw === "" || !Number.isFinite(temperature) || temperature < 0 || temperature > 2) {
      setVersionFormError("Temperature 必须是 0 到 2 之间的数字。");
      return;
    }
    if (maxOutputTokensRaw === "" || !Number.isInteger(maxOutputTokens) || maxOutputTokens < 1 || maxOutputTokens > 1_000_000) {
      setVersionFormError("Max Output Tokens 必须是 1 到 1,000,000 之间的整数。");
      return;
    }
    const draft: ModelConfigVersionDraft = { modelId, temperature, maxOutputTokens };
    setVersionSaving(true);
    try {
      await createModelConfigVersion(connectionId, draft);
      form.reset();
      setVersions(await listModelConfigVersions(connectionId));
      setSuccessNotice("已追加新的不可变配置版本。");
    } catch (reason) {
      setVersionFormError(reason instanceof ApiError ? reason.message : "创建配置版本失败，请稍后重试。");
    } finally {
      setVersionSaving(false);
    }
  };

  const requestDelete = (id: string) => {
    setConfirmDeleteId((current) => current === id ? null : id);
  };

  const runDelete = async (connection: ModelConnection) => {
    if (deletingId !== null) return;
    setDeletingId(connection.id);
    try {
      await deleteModelConnection(connection.id);
      setConfirmDeleteId(null);
      setSuccessNotice("连接已删除（软删除，历史配置版本保留可追溯）。");
      await loadConnections();
    } catch (reason) {
      setConfirmDeleteId(null);
      setLoadError(reason instanceof ApiError ? reason.message : "删除失败，请稍后重试。");
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div className="page">
      <PageHeader
        eyebrow="平台 / 模型接入"
        title="模型连接与生成参数分开版本化"
        description="密钥只写不读；测试连接会向白名单内 Provider 发起只读鉴权探测，并重新校验 DNS 与重定向目标。"
        actions={
          <Button className="button--primary" onClick={() => openDialog({ mode: "create" })}>
            <Glyph name="plus" />新增模型连接
          </Button>
        }
      />
      {successNotice ? (
        <div className="page-notice" role="status">
          <Glyph name="check" size={14} />
          <span>{successNotice}</span>
          <button aria-label="关闭提示" onClick={() => setSuccessNotice(null)}><Glyph name="close" size={14} /></button>
        </div>
      ) : null}
      {loadError ? (
        <div className="load-error" role="alert">
          <Glyph name="warning" size={16} />
          <div><b>无法加载模型连接</b><span>{loadError}</span></div>
          <Button className="button--quiet button--small" onClick={() => void loadConnections()}>重试</Button>
        </div>
      ) : null}
      {connections === null && !loadError ? (
        <div className="model-loading" aria-busy="true">正在加载模型连接…</div>
      ) : null}
      {connections !== null && connections.length === 0 ? (
        <EmptyState title="还没有模型连接" detail="点击右上角“新增模型连接”创建第一条连接，凭据只写不读。" />
      ) : null}
      {connections !== null && connections.length > 0 ? (
        <section className="model-table" aria-label="模型连接列表">
          <div className="model-table__head">
            <span>连接</span><span>Provider / 类型</span><span>Base URL</span><span>凭据</span><span>校验状态</span><span>操作</span>
          </div>
          {connections.map((connection) => {
            const result = testResults[connection.id];
            const error = testErrors[connection.id];
            return (
              <article key={connection.id}>
                <div className="model-name">
                  <span><Glyph name="model" size={18} /></span>
                  <div><b>{connection.name}</b><small>{connection.id}</small></div>
                </div>
                <div><b>{modelProviderLabel(connection.provider)}</b><code>{connection.provider}</code></div>
                <code className="base-url">{connection.baseUrl}</code>
                <div>
                  <Status tone={connection.credentialConfigured ? "success" : "warning"}>
                    {connection.credentialConfigured ? "已配置" : "未配置"}
                  </Status>
                  <small>密钥只写不可读</small>
                </div>
                <div>
                  <Status tone={toStatusTone(connection.validationStatus)}>
                    {connectionValidationLabel(connection.validationStatus)}
                  </Status>
                  {connection.lastValidatedAt ? <small>{formatDateTime(connection.lastValidatedAt)} 校验</small> : null}
                </div>
                <div className="row-actions">
                  <button onClick={() => void runTest(connection)} disabled={testingId !== null}>
                    {testingId === connection.id ? "测试中…" : "测试连接"}
                  </button>
                  <button onClick={() => void toggleVersions(connection.id)}>
                    {versionsFor === connection.id ? "收起版本" : "配置版本"}
                  </button>
                  <button onClick={() => openDialog({ mode: "edit", connection })}>编辑</button>
                  {confirmDeleteId === connection.id ? (
                    <>
                      <button className="row-action--danger" onClick={() => void runDelete(connection)}
                        disabled={deletingId === connection.id}>
                        {deletingId === connection.id ? "删除中…" : "确认删除"}
                      </button>
                      <button onClick={() => setConfirmDeleteId(null)} disabled={deletingId !== null}>取消</button>
                    </>
                  ) : (
                    <button className="row-action--danger" onClick={() => requestDelete(connection.id)}>删除</button>
                  )}
                </div>
                {error ? (
                  <div className="model-inline-note model-inline-note--error" role="alert">
                    <Glyph name="warning" size={15} />
                    <div><b>连接测试失败</b><span>{error}</span></div>
                  </div>
                ) : null}
                {result ? <TestResultNote result={result} /> : null}
                {versionsFor === connection.id ? (
                  <div className="model-versions">
                    <div className="model-versions__head">
                      <b>不可变配置版本</b>
                      <span>版本只追加、不修改；发布时按版本 ID 引用。</span>
                    </div>
                    {versionsError ? <div className="form-error" role="alert">{versionsError}</div> : null}
                    {versions === null && !versionsError ? (
                      <div className="model-loading" aria-busy="true">正在加载配置版本…</div>
                    ) : null}
                    {versions && versions.length === 0 ? (
                      <EmptyState title="还没有配置版本" detail="使用下方表单追加第一个不可变版本。" />
                    ) : null}
                    {versions && versions.length > 0 ? (
                      <div className="version-list">
                        {versions.map((version) => (
                          <div className="version-row" key={version.id}>
                            <code className="version-chip">v{version.version}</code>
                            <code>{version.modelId}</code>
                            <span>temperature <b>{version.temperature}</b></span>
                            <span>maxOutputTokens <b>{version.maxOutputTokens}</b></span>
                            <time>{formatDateTime(version.createdAt)}</time>
                          </div>
                        ))}
                      </div>
                    ) : null}
                    <form className="version-form" onSubmit={(event) => void submitVersion(event, connection.id)} noValidate>
                      <label className="field"><span>Model ID</span><input name="modelId" maxLength={300} placeholder="例如 qwen-max" /></label>
                      <label className="field"><span>Temperature</span><input name="temperature" type="number" step="0.1" min="0" max="2" inputMode="decimal" placeholder="0 到 2" /></label>
                      <label className="field"><span>Max Output Tokens</span><input name="maxOutputTokens" type="number" min="1" max="1000000" inputMode="numeric" placeholder="1 到 1,000,000" /></label>
                      <Button type="submit" className="button--primary button--small" disabled={versionSaving}>
                        {versionSaving ? "追加中…" : "追加版本"}
                      </Button>
                      {versionFormError ? <div className="form-error" role="alert">{versionFormError}</div> : null}
                    </form>
                  </div>
                ) : null}
              </article>
            );
          })}
        </section>
      ) : null}
      <div className="model-footnotes">
        <div><b>SSRF 防护</b><p>Base URL 必须命中管理员白名单，并在 DNS 解析与重定向后再次校验。</p></div>
        <div><b>配置快照</b><p>发布 Manifest 记录 Provider、Model ID 和生成参数哈希，不记录 API Key。</p></div>
        <div><b>离线校验</b><p>连接测试只验证安全配置边界，不发起网络请求，也不代表供应商真实连通。</p></div>
      </div>
      {dialog ? (
        <ConnectionDialog
          dialog={dialog}
          saving={saving}
          formError={formError}
          formFieldErrors={formFieldErrors}
          onClose={closeDialog}
          onSubmit={(event) => void submitConnection(event)}
        />
      ) : null}
    </div>
  );
}
