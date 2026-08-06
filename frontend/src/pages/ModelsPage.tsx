import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { Button, EmptyState, Glyph, PageHeader, Status } from "../components/Ui";
import {
  ApiError,
  createEmbeddingProfile,
  createModelConfigVersion,
  createModelEndpointRule,
  deleteModelConnection,
  deleteModelEndpointRule,
  listEmbeddingProfiles,
  listModelConfigVersions,
  listModelConnections,
  listModelEndpointRules,
  setupModelConnection,
  testModelConnection,
  updateModelConnection,
  updateModelEndpointRule,
} from "../lib/api";
import type {
  EmbeddingProfile,
  EmbeddingProfileDraft,
  ModelConfigVersion,
  ModelConfigVersionDraft,
  ModelConnection,
  ModelConnectionDraft,
  ModelConnectionSetupDraft,
  ModelConnectionTestResult,
  ModelEndpointRule,
  ModelEndpointRuleDraft,
  ModelProvider,
} from "../lib/api";
import { connectionTestSummary, connectionValidationLabel, modelProviderLabel, toStatusTone } from "../domain";

type ConnectionDialogState = { mode: "create" } | { mode: "edit"; connection: ModelConnection };
type EndpointRuleDialogState = { mode: "create" } | { mode: "edit"; rule: ModelEndpointRule };

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

function EndpointRuleDialog({
  dialog,
  saving,
  error,
  onClose,
  onSubmit,
}: {
  dialog: EndpointRuleDialogState;
  saving: boolean;
  error: string | null;
  onClose: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const editing = dialog.mode === "edit" ? dialog.rule : null;

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (!node.open) node.showModal();
    return () => { if (node.open) node.close(); };
  }, []);

  return (
    <dialog ref={ref} className="model-dialog" aria-labelledby="endpoint-rule-dialog-title" onCancel={onClose}>
      <form className="model-dialog__form" onSubmit={onSubmit} noValidate>
        <header className="model-dialog__head">
          <div>
            <h2 id="endpoint-rule-dialog-title">{editing ? "编辑可信模型主机" : "新增可信模型主机"}</h2>
            <p>规则保存后立即生效，API 和 Worker 每次请求及重定向都会重新校验。</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={saving}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          <label className="field">
            <span>主机名或 IPv4 地址</span>
            <input name="host" autoFocus autoComplete="off" maxLength={253} defaultValue={editing?.host}
              placeholder="例如 llm-gateway.bank.local" />
            <small>只填写精确主机，不包含协议、路径、通配符或端口。</small>
          </label>
          <label className="field">
            <span>允许端口</span>
            <input name="allowedPorts" autoComplete="off" defaultValue={editing?.allowedPorts.join(", ") ?? "443"}
              placeholder="例如 443, 8000" />
            <small>多个端口使用逗号分隔，最多 32 个。</small>
          </label>
          <div className="field field--row">
            <span>允许解析到内网地址</span>
            <label className="switch">
              <input type="checkbox" name="allowPrivateAddresses" aria-label="允许解析到内网地址"
                defaultChecked={editing?.allowPrivateAddresses ?? true} />
              <span />
            </label>
          </div>
          <div className="field field--row">
            <span>允许使用 HTTP</span>
            <label className="switch">
              <input type="checkbox" name="allowHttp" aria-label="允许使用 HTTP"
                defaultChecked={editing?.allowHttp ?? false} />
              <span />
            </label>
          </div>
          <div className="policy-safety-note">
            <Glyph name="lock" size={14} />
            <span>回环、链路本地、组播和保留地址始终禁止，不受本规则开关影响。</span>
          </div>
          {error ? <div className="form-error" role="alert">{error}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={saving}>取消</Button>
          <Button type="submit" className="button--primary" disabled={saving}>
            {saving ? "保存中…" : "保存并立即生效"}
          </Button>
        </footer>
      </form>
    </dialog>
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
            <p>{editing ? "凭据为只写字段，本页永远不会回显已保存的密钥。" : "填写连接地址、API Key 和 Model ID，保存后立即执行连通测试。"}</p>
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
              placeholder="http://llm-gateway.bank.local:8000/v1" aria-invalid={Boolean(formFieldErrors.baseUrl)} />
            <small>系统会自动登记该地址的精确主机和端口。</small>
            {formFieldErrors.baseUrl ? <small className="field-error">{formFieldErrors.baseUrl}</small> : null}
          </label>
          {!editing ? (
            <label className="field">
              <span>Model ID</span>
              <input name="modelId" autoComplete="off" maxLength={300}
                aria-label="Model ID"
                placeholder="例如 MiniMax-M2.5、qwen-max 或本地模型名称"
                aria-invalid={Boolean(formFieldErrors.modelId)} />
              <small>按模型服务实际提供的 ID 填写，不限制供应商的模型列表。</small>
              {formFieldErrors.modelId ? <small className="field-error">{formFieldErrors.modelId}</small> : null}
            </label>
          ) : null}
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
          {!editing ? (
            <div className="field field--row">
              <span>允许访问内网地址</span>
              <label className="switch">
                <input type="checkbox" name="allowPrivateAddresses" aria-label="允许访问内网地址" />
                <span />
              </label>
              <small>仅本地部署模型或银行内网网关需要开启；系统会按 Base URL 自动登记精确主机和端口。</small>
            </div>
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
            {saving ? (editing ? "保存中…" : "保存并测试中…") : editing ? "保存修改" : "保存并测试"}
          </Button>
        </footer>
      </form>
    </dialog>
  );
}

export function ModelsPage() {
  const [endpointRules, setEndpointRules] = useState<ModelEndpointRule[] | null>(null);
  const [endpointRuleError, setEndpointRuleError] = useState<string | null>(null);
  const [endpointRuleDialog, setEndpointRuleDialog] = useState<EndpointRuleDialogState | null>(null);
  const [endpointRuleSaving, setEndpointRuleSaving] = useState(false);
  const [endpointRuleFormError, setEndpointRuleFormError] = useState<string | null>(null);
  const [endpointRuleDeleteConfirmId, setEndpointRuleDeleteConfirmId] = useState<string | null>(null);
  const [endpointRuleDeletingId, setEndpointRuleDeletingId] = useState<string | null>(null);
  const [connections, setConnections] = useState<ModelConnection[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [successNotice, setSuccessNotice] = useState<string | null>(null);
  const [warningNotice, setWarningNotice] = useState<string | null>(null);

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
  const [embeddingProfiles, setEmbeddingProfiles] = useState<EmbeddingProfile[] | null>(null);
  const [embeddingError, setEmbeddingError] = useState<string | null>(null);
  const [embeddingSaving, setEmbeddingSaving] = useState(false);

  const loadEndpointRules = useCallback(async () => {
    setEndpointRuleError(null);
    try {
      setEndpointRules(await listModelEndpointRules());
    } catch (reason) {
      setEndpointRules(null);
      setEndpointRuleError(reason instanceof ApiError ? reason.message : "无法读取模型访问策略。");
    }
  }, []);

  useEffect(() => {
    void loadEndpointRules();
  }, [loadEndpointRules]);

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

  const loadEmbeddingProfiles = useCallback(async () => {
    setEmbeddingError(null);
    try {
      setEmbeddingProfiles(await listEmbeddingProfiles());
    } catch (reason) {
      setEmbeddingProfiles(null);
      setEmbeddingError(reason instanceof ApiError ? reason.message : "无法读取 Embedding 配置版本。");
    }
  }, []);

  useEffect(() => {
    void loadEmbeddingProfiles();
  }, [loadEmbeddingProfiles]);

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
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const name = String(form.get("name") ?? "").trim();
    const provider = String(form.get("provider") ?? "") as ModelProvider;
    const baseUrl = String(form.get("baseUrl") ?? "").trim();
    const credential = String(form.get("credential") ?? "");
    const modelId = String(form.get("modelId") ?? "").trim();
    const enabled = form.get("enabled") === "on";
    if (!name) {
      setFormError("请输入连接名称。");
      return;
    }
    if (!/^https?:\/\/.+/.test(baseUrl)) {
      setFormError("Base URL 必须是以 http:// 或 https:// 开头的完整地址。");
      return;
    }
    if (dialog.mode === "create" && !modelId) {
      setFormError("请输入 Model ID。");
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
        closeDialog();
        setSuccessNotice("连接已更新，列表已刷新。");
        setWarningNotice(null);
        await loadConnections();
      } else {
        const setupDraft: ModelConnectionSetupDraft = {
          ...draft,
          modelId,
          allowPrivateAddresses: form.get("allowPrivateAddresses") === "on",
        };
        const result = await setupModelConnection(setupDraft);
        setTestResults((results) => ({ ...results, [result.connection.id]: result.connectionTest }));
        closeDialog();
        await Promise.all([loadEndpointRules(), loadConnections()]);
        if (result.connectionTest.connectivityVerified) {
          setSuccessNotice(`${result.connection.name} 已保存，模型 ${result.configVersion.modelId} 连通测试通过。`);
          setWarningNotice(null);
        } else {
          setSuccessNotice(null);
          setWarningNotice(`${result.connection.name} 和模型配置已保存，但连通测试未通过；请检查地址、API Key 后重新测试。`);
        }
      }
    } catch (reason) {
      if (reason instanceof ApiError) {
        setFormError(reason.message);
        setFormFieldErrors(fieldErrorsToRecord(reason.errors));
      } else {
        setFormError("保存失败，请稍后重试。");
      }
    } finally {
      const credentialInput = formElement.elements.namedItem("credential");
      if (credentialInput instanceof HTMLInputElement) credentialInput.value = "";
      setSaving(false);
    }
  };

  const openEndpointRuleDialog = (state: EndpointRuleDialogState) => {
    setEndpointRuleFormError(null);
    setEndpointRuleDialog(state);
  };

  const submitEndpointRule = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (endpointRuleSaving || !endpointRuleDialog) return;
    setEndpointRuleFormError(null);
    const form = new FormData(event.currentTarget);
    const host = String(form.get("host") ?? "").trim();
    const rawPortValues = String(form.get("allowedPorts") ?? "").split(",").map((value) => value.trim());
    const ports = [...new Set(rawPortValues.map(Number))];
    if (!host || host.includes("://") || /[*/:]/.test(host)) {
      setEndpointRuleFormError("请填写不含协议、路径、通配符或端口的精确主机名或 IPv4 地址。");
      return;
    }
    if (rawPortValues.some((value) => value === "") || ports.length === 0 || ports.length > 32
      || ports.some((port) => !Number.isInteger(port) || port < 1 || port > 65_535)) {
      setEndpointRuleFormError("请填写 1 到 65535 之间的端口，多个端口使用逗号分隔，最多 32 个。");
      return;
    }
    const draft: ModelEndpointRuleDraft = {
      host,
      allowedPorts: ports,
      allowHttp: form.get("allowHttp") === "on",
      allowPrivateAddresses: form.get("allowPrivateAddresses") === "on",
    };
    setEndpointRuleSaving(true);
    try {
      if (endpointRuleDialog.mode === "edit") {
        await updateModelEndpointRule(endpointRuleDialog.rule.id, draft);
      } else {
        await createModelEndpointRule(draft);
      }
      setEndpointRuleDialog(null);
      setSuccessNotice("模型访问策略已保存并立即生效，无需重启服务。");
      await loadEndpointRules();
    } catch (reason) {
      setEndpointRuleFormError(reason instanceof ApiError ? reason.message : "保存模型访问策略失败。");
    } finally {
      setEndpointRuleSaving(false);
    }
  };

  const deleteEndpointRule = async (rule: ModelEndpointRule) => {
    if (endpointRuleDeletingId !== null) return;
    setEndpointRuleDeletingId(rule.id);
    setEndpointRuleError(null);
    try {
      await deleteModelEndpointRule(rule.id);
      setEndpointRuleDeleteConfirmId(null);
      setSuccessNotice(`可信模型主机 ${rule.host} 已删除。`);
      await loadEndpointRules();
    } catch (reason) {
      setEndpointRuleError(reason instanceof ApiError ? reason.message : "删除可信模型主机失败。");
    } finally {
      setEndpointRuleDeletingId(null);
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

  const submitEmbeddingProfile = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (embeddingSaving) return;
    setEmbeddingError(null);
    const form = event.currentTarget;
    const data = new FormData(form);
    const modelConnectionId = String(data.get("modelConnectionId") ?? "");
    const modelId = String(data.get("modelId") ?? "").trim();
    const dimension = Number(data.get("dimension"));
    const profileVersion = String(data.get("profileVersion") ?? "").trim();
    if (!modelConnectionId || !modelId || !profileVersion || !Number.isInteger(dimension)
      || dimension < 1 || dimension > 2000) {
      setEmbeddingError("请选择已验证连接，并填写 Model ID、版本标识和 1–2000 的向量维度。");
      return;
    }
    const draft: EmbeddingProfileDraft = {
      modelConnectionId,
      modelId,
      dimension,
      profileVersion,
      normalization: String(data.get("normalization")) as EmbeddingProfileDraft["normalization"],
      distanceFunction: String(data.get("distanceFunction")) as EmbeddingProfileDraft["distanceFunction"],
    };
    setEmbeddingSaving(true);
    try {
      await createEmbeddingProfile(draft);
      form.reset();
      setSuccessNotice("Embedding 配置已激活，后续入库将按该不可变版本写入向量索引。");
      await loadEmbeddingProfiles();
    } catch (reason) {
      setEmbeddingError(reason instanceof ApiError ? reason.message : "激活 Embedding 配置失败。");
    } finally {
      setEmbeddingSaving(false);
    }
  };

  const verifiedConnections = (connections ?? []).filter((connection) => connection.enabled
    && connection.credentialConfigured && connection.validationStatus === "CONNECTIVITY_VERIFIED");

  return (
    <div className="page">
      <PageHeader
        eyebrow="平台 / 模型接入"
        title="模型连接"
        description="填写 Provider、Base URL、API Key 和 Model ID；密钥只写不读，保存后自动测试连接。"
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
      {warningNotice ? (
        <div className="page-notice page-notice--warning" role="status">
          <Glyph name="warning" size={14} />
          <span>{warningNotice}</span>
          <button aria-label="关闭提示" onClick={() => setWarningNotice(null)}><Glyph name="close" size={14} /></button>
        </div>
      ) : null}
      <details className="settings-disclosure">
        <summary>
          <span className="settings-disclosure__mark"><Glyph name="lock" size={16} /></span>
          <span className="settings-disclosure__copy">
            <b>网络访问策略</b>
            <small>{endpointRules === null ? "正在读取可信主机…" : `已配置 ${endpointRules.length} 条页面规则；本地和银行内网模型按需调整`}</small>
          </span>
          <Status tone="neutral">高级设置</Status>
          <Glyph name="chevron" size={16} />
        </summary>
        <div className="settings-disclosure__body">
      <section className="model-policy" aria-labelledby="model-policy-title">
        <header className="model-policy__head">
          <div className="model-policy__mark"><Glyph name="lock" size={18} /></div>
          <div>
            <span>INTRANET MODEL ACCESS</span>
            <h2 id="model-policy-title">模型访问策略</h2>
            <p>管理员在页面维护可信主机、端口和内网访问范围；保存后 API 与 Worker 立即生效。</p>
          </div>
          <Button className="button--quiet button--small" onClick={() => openEndpointRuleDialog({ mode: "create" })}>
            <Glyph name="plus" size={14} />新增可信主机
          </Button>
        </header>
        {endpointRuleError ? (
          <div className="load-error" role="alert">
            <Glyph name="warning" size={16} />
            <div><b>无法处理模型访问策略</b><span>{endpointRuleError}</span></div>
            <Button className="button--quiet button--small" onClick={() => void loadEndpointRules()}>重试</Button>
          </div>
        ) : null}
        {endpointRules === null && !endpointRuleError ? <div className="model-loading" aria-busy="true">正在读取可信主机…</div> : null}
        {endpointRules?.length === 0 ? (
          <EmptyState title="还没有页面配置的可信主机" detail="新增银行内网模型网关或本地部署模型的精确主机和端口。启动默认主机仍保持可用。" />
        ) : null}
        {endpointRules && endpointRules.length > 0 ? (
          <div className="model-policy__rules">
            {endpointRules.map((rule) => (
              <article key={rule.id}>
                <div><b>{rule.host}</b><code>{rule.allowedPorts.join(", ")}</code></div>
                <Status tone={rule.allowPrivateAddresses ? "info" : "neutral"}>
                  {rule.allowPrivateAddresses ? "允许内网" : "仅公网地址"}
                </Status>
                <Status tone={rule.allowHttp ? "warning" : "success"}>
                  {rule.allowHttp ? "HTTP / HTTPS" : "仅 HTTPS"}
                </Status>
                <time>{formatDateTime(rule.updatedAt)}</time>
                <div className="row-actions">
                  <button onClick={() => openEndpointRuleDialog({ mode: "edit", rule })}>编辑</button>
                  {endpointRuleDeleteConfirmId === rule.id ? (
                    <>
                      <button className="row-action--danger" onClick={() => void deleteEndpointRule(rule)}
                        disabled={endpointRuleDeletingId !== null}>
                        {endpointRuleDeletingId === rule.id ? "删除中…" : "确认删除"}
                      </button>
                      <button onClick={() => setEndpointRuleDeleteConfirmId(null)}>取消</button>
                    </>
                  ) : <button className="row-action--danger" onClick={() => setEndpointRuleDeleteConfirmId(rule.id)}>删除</button>}
                </div>
              </article>
            ))}
          </div>
        ) : null}
      </section>
        </div>
      </details>
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
      <details className="settings-disclosure settings-disclosure--embedding">
        <summary>
          <span className="settings-disclosure__mark"><Glyph name="search" size={16} /></span>
          <span className="settings-disclosure__copy">
            <b>Embedding 与中文稠密检索</b>
            <small>仅在需要素材向量检索时配置；不会影响基础模型连接</small>
          </span>
          <Status tone={embeddingProfiles?.find((profile) => profile.active) ? "success" : "warning"}>
            {embeddingProfiles?.find((profile) => profile.active) ? "已激活" : "未配置"}
          </Status>
          <Glyph name="chevron" size={16} />
        </summary>
        <div className="settings-disclosure__body">
      <section className="embedding-panel" aria-labelledby="embedding-title">
        <header className="embedding-panel__head">
          <div className="embedding-mark"><Glyph name="search" size={18} /></div>
          <div>
            <span>VECTOR PROFILE / ACTIVE POINTER</span>
            <h2 id="embedding-title">Embedding 与中文稠密检索</h2>
            <p>配置版本不可变；激活指针单独更新，并为该维度建立局部 HNSW 索引。</p>
          </div>
          {embeddingProfiles?.find((profile) => profile.active) ? <Status tone="success">已激活</Status> : <Status tone="warning">未配置</Status>}
        </header>
        {embeddingError ? <div className="form-error" role="alert">{embeddingError}</div> : null}
        <div className="embedding-panel__body">
          <form className="embedding-form" onSubmit={(event) => void submitEmbeddingProfile(event)} noValidate>
            <label className="field">
              <span>已验证连接</span>
              <select name="modelConnectionId" defaultValue="" disabled={embeddingSaving}>
                <option value="">请选择</option>
                {verifiedConnections.map((connection) => (
                  <option key={connection.id} value={connection.id}>{connection.name} · {modelProviderLabel(connection.provider)}</option>
                ))}
              </select>
            </label>
            <label className="field"><span>Embedding Model ID</span><input name="modelId" maxLength={200} placeholder="例如 text-embedding-v4" /></label>
            <label className="field"><span>向量维度</span><input name="dimension" type="number" min="1" max="2000" defaultValue="1024" /></label>
            <label className="field"><span>版本标识</span><input name="profileVersion" maxLength={100} placeholder="例如 2026-08" /></label>
            <label className="field"><span>归一化</span><select name="normalization" defaultValue="L2"><option value="L2">L2</option><option value="NONE">不处理</option></select></label>
            <label className="field"><span>距离函数</span><select name="distanceFunction" defaultValue="COSINE"><option value="COSINE">Cosine</option><option value="L2">L2</option></select></label>
            <Button type="submit" className="button--primary button--small" disabled={embeddingSaving || verifiedConnections.length === 0}>
              {embeddingSaving ? "激活中…" : "创建并激活"}
            </Button>
            {verifiedConnections.length === 0 ? <small>先为一个已启用且已配置凭据的模型连接完成真实连通测试。</small> : null}
          </form>
          <div className="embedding-history" aria-label="Embedding 配置历史">
            {embeddingProfiles === null && !embeddingError ? <div className="model-loading" aria-busy="true">正在读取向量配置…</div> : null}
            {embeddingProfiles?.length === 0 ? <EmptyState title="还没有向量配置" detail="完成模型连接测试后，在左侧创建第一个不可变 Embedding 配置。" /> : null}
            {embeddingProfiles?.map((profile) => (
              <article key={profile.id} className={profile.active ? "active" : ""}>
                <span className="embedding-history__signal" />
                <div><b>{profile.modelId}</b><code>{profile.provider} · {profile.profileVersion}</code></div>
                <span>{profile.dimension}d</span>
                <span>{profile.normalization} / {profile.distanceFunction}</span>
                <time>{formatDateTime(profile.createdAt)}</time>
                {profile.active ? <Status tone="success">当前索引</Status> : <Status tone="neutral">历史</Status>}
              </article>
            ))}
          </div>
        </div>
      </section>
        </div>
      </details>
      <div className="model-footnotes">
        <div><b>内网访问边界</b><p>可信主机、端口、HTTP 和私网地址均由管理员在本页配置，变更立即生效。</p></div>
        <div><b>配置快照</b><p>发布 Manifest 记录 Provider、Model ID 和生成参数哈希，不记录 API Key。</p></div>
        <div><b>真实连通</b><p>连接测试会发起最小只读鉴权探测；只有通过的连接才能激活 Embedding 配置。</p></div>
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
      {endpointRuleDialog ? (
        <EndpointRuleDialog dialog={endpointRuleDialog} saving={endpointRuleSaving}
          error={endpointRuleFormError} onClose={() => setEndpointRuleDialog(null)}
          onSubmit={(event) => void submitEndpointRule(event)} />
      ) : null}
    </div>
  );
}
