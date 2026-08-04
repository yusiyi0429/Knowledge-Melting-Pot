import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { Button, EmptyState, Glyph, PageHeader, Status } from "../components/Ui";
import {
  ApiError,
  createUser,
  getCurrentUser,
  listUsers,
  resetUserPassword,
  updateUser,
} from "../lib/api";
import type { AuthenticatedUser, UpdateUserPatch, UserAccount, UserRole } from "../lib/api";
import { toStatusTone } from "../domain";

const ROLES: UserRole[] = ["OPERATOR", "PUBLISHER", "ADMIN"];

const ROLE_LABELS: Record<UserRole, string> = {
  OPERATOR: "运营",
  PUBLISHER: "发布",
  ADMIN: "管理员",
};

type DialogState =
  | { mode: "create" }
  | { mode: "edit"; user: UserAccount }
  | { mode: "reset"; user: UserAccount };

function fieldErrorsToRecord(errors: ApiError["errors"]): Record<string, string> {
  const record: Record<string, string> = {};
  for (const error of errors ?? []) {
    if (!record[error.field]) record[error.field] = error.message;
  }
  return record;
}

function sameRoles(a: Set<UserRole>, b: Set<UserRole>): boolean {
  return a.size === b.size && [...a].every((role) => b.has(role));
}

function RoleChecks({
  value,
  disabledRole,
  onChange,
}: {
  value: Set<UserRole>;
  disabledRole?: UserRole;
  onChange: (role: UserRole, checked: boolean) => void;
}) {
  return (
    <div className="role-checks" role="group" aria-label="角色">
      {ROLES.map((role) => (
        <label key={role} className="role-check">
          <input
            type="checkbox"
            name="roles"
            value={role}
            checked={value.has(role)}
            disabled={role === disabledRole}
            onChange={(event) => onChange(role, event.currentTarget.checked)}
          />
          <span>{role}</span>
          <small>{ROLE_LABELS[role]}</small>
        </label>
      ))}
    </div>
  );
}

function CreateUserDialog({
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
  const [selected, setSelected] = useState<Set<UserRole>>(new Set());

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (!node.open) node.showModal();
    return () => {
      if (node.open) node.close();
    };
  }, []);

  return (
    <dialog ref={ref} className="user-dialog" aria-labelledby="user-dialog-title" onCancel={onClose}>
      <form className="model-dialog__form" onSubmit={onSubmit} noValidate>
        <header className="model-dialog__head">
          <div>
            <h2 id="user-dialog-title">新增用户</h2>
            <p>账号只能由管理员创建；初始密码只发送到同源 API，不写入浏览器存储。</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={saving}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          <label className="field">
            <span>用户名</span>
            <input name="username" autoFocus autoComplete="off" maxLength={100}
              placeholder="字母或数字开头，可含 . _ -" aria-invalid={Boolean(formFieldErrors.username)} />
            <small>3–100 位，仅限 ASCII 字母、数字、点、下划线和连字符。</small>
            {formFieldErrors.username ? <small className="field-error">{formFieldErrors.username}</small> : null}
          </label>
          <label className="field">
            <span>显示名</span>
            <input name="displayName" autoComplete="off" maxLength={200}
              placeholder="例如：李楠" aria-invalid={Boolean(formFieldErrors.displayName)} />
            {formFieldErrors.displayName ? <small className="field-error">{formFieldErrors.displayName}</small> : null}
          </label>
          <label className="field">
            <span>初始密码（只写）</span>
            <input name="initialPassword" type="password" autoComplete="new-password" maxLength={128}
              placeholder="至少 12 个字符" aria-invalid={Boolean(formFieldErrors.initialPassword)} />
            <small>至少 12 个字符；新用户首次登录必须修改密码。</small>
            {formFieldErrors.initialPassword ? <small className="field-error">{formFieldErrors.initialPassword}</small> : null}
          </label>
          <div className="field">
            <span>角色（至少一个）</span>
            <RoleChecks
              value={selected}
              onChange={(role, checked) => {
                setSelected((prev) => {
                  const next = new Set(prev);
                  if (checked) next.add(role);
                  else next.delete(role);
                  return next;
                });
              }}
            />
            {selected.size === 0 ? <small className="field-hint">至少选择一个角色。</small> : null}
            {formFieldErrors.roles ? <small className="field-error">{formFieldErrors.roles}</small> : null}
          </div>
          {formError ? <div className="form-error" role="alert">{formError}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={saving}>取消</Button>
          <Button type="submit" className="button--primary" disabled={saving}>
            {saving ? "创建中…" : "创建用户"}
          </Button>
        </footer>
      </form>
    </dialog>
  );
}

function EditUserDialog({
  user,
  currentUserId,
  saving,
  formError,
  formFieldErrors,
  onClose,
  onSubmit,
}: {
  user: UserAccount;
  currentUserId: string | null;
  saving: boolean;
  formError: string | null;
  formFieldErrors: Record<string, string>;
  onClose: () => void;
  onSubmit: (changed: { displayName: string; enabled: boolean; roles: UserRole[]; revokesSessions: boolean }) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const isSelf = currentUserId === user.id;
  const [displayName, setDisplayName] = useState(user.displayName);
  const [enabled, setEnabled] = useState(user.enabled);
  const [selected, setSelected] = useState<Set<UserRole>>(() => new Set(user.roles));

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (!node.open) node.showModal();
    return () => {
      if (node.open) node.close();
    };
  }, []);

  const rolesChanged = !sameRoles(selected, new Set(user.roles));
  const enabledChanged = enabled !== user.enabled;
  const displayNameChanged = displayName.trim() !== user.displayName;
  const hasChanges = displayNameChanged || rolesChanged || enabledChanged;
  const revokesSessions = rolesChanged || enabledChanged;
  const cannotRemoveOwnAdmin = isSelf && user.roles.includes("ADMIN");

  return (
    <dialog ref={ref} className="user-dialog" aria-labelledby="user-dialog-title" onCancel={onClose}>
      <form
        className="model-dialog__form"
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit({ displayName: displayName.trim(), enabled, roles: [...selected], revokesSessions });
        }}
        noValidate
      >
        <header className="model-dialog__head">
          <div>
            <h2 id="user-dialog-title">编辑用户</h2>
            <p>@{user.username}{isSelf ? " · 当前账号，危险变更已在页面内禁用" : ""}</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={saving}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          {revokesSessions ? (
            <div className="dialog-warning" role="note">
              <Glyph name="warning" size={15} />
              <span>角色或启停变更保存后，该用户的所有现有会话将立即失效，需要重新登录。</span>
            </div>
          ) : null}
          <label className="field">
            <span>显示名</span>
            <input name="displayName" value={displayName} maxLength={200}
              onChange={(event) => setDisplayName(event.currentTarget.value)}
              aria-invalid={Boolean(formFieldErrors.displayName)} />
            {formFieldErrors.displayName ? <small className="field-error">{formFieldErrors.displayName}</small> : null}
          </label>
          <div className="field field--row">
            <span>启用该账号</span>
            <label className="switch">
              <input
                type="checkbox"
                name="enabled"
                aria-label="启用该账号"
                checked={enabled}
                disabled={isSelf}
                onChange={(event) => setEnabled(event.currentTarget.checked)}
              />
              <span />
            </label>
          </div>
          {isSelf ? <small className="field-hint">不能停用自己的账号。</small> : null}
          <div className="field">
            <span>角色</span>
            <RoleChecks
              value={selected}
              disabledRole={cannotRemoveOwnAdmin ? "ADMIN" : undefined}
              onChange={(role, checked) => {
                setSelected((prev) => {
                  const next = new Set(prev);
                  if (checked) next.add(role);
                  else next.delete(role);
                  return next;
                });
              }}
            />
            {cannotRemoveOwnAdmin ? <small className="field-hint">不能移除自己的 ADMIN 角色。</small> : null}
            {selected.size === 0 ? <small className="field-error">至少保留一个角色。</small> : null}
            {formFieldErrors.roles ? <small className="field-error">{formFieldErrors.roles}</small> : null}
          </div>
          {formError ? <div className="form-error" role="alert">{formError}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={saving}>取消</Button>
          <Button
            type="submit"
            className="button--primary"
            disabled={saving || selected.size === 0 || !displayName.trim() || !hasChanges}
          >
            {saving ? "保存中…" : "保存修改"}
          </Button>
        </footer>
      </form>
    </dialog>
  );
}

function ResetPasswordDialog({
  user,
  saving,
  formError,
  onClose,
  onSubmit,
}: {
  user: UserAccount;
  saving: boolean;
  formError: string | null;
  onClose: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [showPassword, setShowPassword] = useState(false);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (!node.open) node.showModal();
    return () => {
      if (node.open) node.close();
    };
  }, []);

  return (
    <dialog ref={ref} className="user-dialog" aria-labelledby="user-dialog-title" onCancel={onClose}>
      <form className="model-dialog__form" onSubmit={onSubmit} noValidate>
        <header className="model-dialog__head">
          <div>
            <h2 id="user-dialog-title">重置密码</h2>
            <p>@{user.username} · {user.displayName}</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={saving}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          <div className="dialog-warning" role="note">
            <Glyph name="warning" size={15} />
            <span>重置后该用户的所有现有会话将立即失效，且下次登录必须修改密码。此操作会审计留痕，密码本身不会被记录。</span>
          </div>
          <label className="field">
            <span>新密码（只写）</span>
            <div className="password-field">
              <input name="newPassword" type={showPassword ? "text" : "password"} autoFocus
                autoComplete="new-password" maxLength={128} placeholder="至少 12 个字符" />
              <button type="button" onClick={() => setShowPassword((value) => !value)}>
                {showPassword ? "隐藏" : "显示"}
              </button>
            </div>
            <small>至少 12 个字符；由服务端校验并哈希存储。</small>
          </label>
          {formError ? <div className="form-error" role="alert">{formError}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          <Button type="button" className="button--quiet" onClick={onClose} disabled={saving}>取消</Button>
          <Button type="submit" className="button--primary button--danger-action" disabled={saving}>
            {saving ? "重置中…" : "确认重置密码"}
          </Button>
        </footer>
      </form>
    </dialog>
  );
}

export function UsersPage() {
  const [users, setUsers] = useState<UserAccount[] | null>(null);
  const [currentUser, setCurrentUser] = useState<AuthenticatedUser | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [successNotice, setSuccessNotice] = useState<string | null>(null);

  const [dialog, setDialog] = useState<DialogState | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [formFieldErrors, setFormFieldErrors] = useState<Record<string, string>>({});
  const dialogTrigger = useRef<HTMLElement | null>(null);

  const loadAll = useCallback(async () => {
    setLoadError(null);
    try {
      const [me, accounts] = await Promise.all([getCurrentUser(), listUsers()]);
      setCurrentUser(me);
      setUsers(accounts);
    } catch (reason) {
      setCurrentUser(null);
      setUsers(null);
      setLoadError(reason instanceof ApiError ? reason.message : "无法连接 API，请确认已登录后重试。");
    }
  }, []);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const openDialog = (state: DialogState) => {
    dialogTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setFormError(null);
    setFormFieldErrors({});
    setDialog(state);
  };

  const closeDialog = () => {
    setDialog(null);
    dialogTrigger.current?.focus();
  };

  const submitCreate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving || dialog?.mode !== "create") return;
    const form = event.currentTarget;
    const formData = new FormData(form);
    const username = String(formData.get("username") ?? "").trim();
    const displayName = String(formData.get("displayName") ?? "").trim();
    const initialPassword = String(formData.get("initialPassword") ?? "");
    const roles = formData.getAll("roles").map(String) as UserRole[];
    if (!username || !displayName) {
      setFormError("请填写用户名和显示名。");
      return;
    }
    if (initialPassword.length < 12) {
      setFormError("初始密码至少需要 12 个字符。");
      return;
    }
    if (roles.length === 0) {
      setFormError("至少选择一个角色。");
      return;
    }
    setSaving(true);
    try {
      await createUser({ username, displayName, initialPassword, roles });
      closeDialog();
      setSuccessNotice("用户已创建；首次登录必须修改初始密码。");
      await loadAll();
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

  const submitEdit = async (changed: {
    displayName: string;
    enabled: boolean;
    roles: UserRole[];
    revokesSessions: boolean;
  }) => {
    if (saving || dialog?.mode !== "edit") return;
    setFormError(null);
    setFormFieldErrors({});
    const patch: UpdateUserPatch = {};
    if (changed.displayName !== dialog.user.displayName) patch.displayName = changed.displayName;
    if (changed.enabled !== dialog.user.enabled) patch.enabled = changed.enabled;
    if (!sameRoles(new Set(changed.roles), new Set(dialog.user.roles))) patch.roles = changed.roles;
    if (Object.keys(patch).length === 0) return;
    setSaving(true);
    try {
      await updateUser(dialog.user.id, patch);
      closeDialog();
      setSuccessNotice(
        changed.revokesSessions
          ? "用户已更新；其现有会话已全部失效，需要重新登录。"
          : "用户已更新。",
      );
      await loadAll();
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

  const submitReset = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving || dialog?.mode !== "reset") return;
    const form = event.currentTarget;
    const formData = new FormData(form);
    const newPassword = String(formData.get("newPassword") ?? "");
    if (newPassword.length < 12) {
      setFormError("新密码至少需要 12 个字符。");
      return;
    }
    setSaving(true);
    try {
      await resetUserPassword(dialog.user.id, newPassword);
      closeDialog();
      setSuccessNotice("密码已重置；该用户下次登录必须修改密码，其现有会话已全部失效。");
    } catch (reason) {
      if (reason instanceof ApiError) {
        setFormError(reason.message);
        setFormFieldErrors(fieldErrorsToRecord(reason.errors));
      } else {
        setFormError("重置失败，请稍后重试。");
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="page">
      <PageHeader
        eyebrow="平台 / 访问治理"
        title="用户与可组合角色"
        description="账号只能由管理员创建；Operator、Publisher、Admin 权限按职责组合。修改角色或启停会使该用户现有会话失效。"
        actions={
          <Button className="button--primary" onClick={() => openDialog({ mode: "create" })}>
            <Glyph name="plus" />新增用户
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
          <div><b>无法加载用户列表</b><span>{loadError}</span></div>
          <Button className="button--quiet button--small" onClick={() => void loadAll()}>重试</Button>
        </div>
      ) : null}
      <div className="role-strip">
        <div><b>OPERATOR</b><span>管理场景、素材、文档与资产</span></div>
        <div><b>PUBLISHER</b><span>执行发布预检与确认</span></div>
        <div><b>ADMIN</b><span>管理用户、模型和全局模板</span></div>
      </div>
      {users === null && !loadError ? (
        <div className="model-loading" aria-busy="true">正在加载用户列表…</div>
      ) : null}
      {users !== null && users.length === 0 ? (
        <EmptyState title="还没有用户" detail="点击右上角“新增用户”创建第一个账号。" />
      ) : null}
      {users !== null && users.length > 0 ? (
        <section className="user-table" aria-label="用户列表">
          <div className="user-table__head"><span>用户</span><span>角色</span><span>状态</span><span>操作</span></div>
          {users.map((user) => {
            const isSelf = currentUser?.id === user.id;
            return (
              <article key={user.id}>
                <div className="user-identity">
                  <span>{user.displayName.slice(0, 1)}</span>
                  <div>
                    <b>{user.displayName}{isSelf ? <small>（当前账号）</small> : null}</b>
                    <small>@{user.username}</small>
                  </div>
                </div>
                <div className="role-list">
                  {user.roles.length
                    ? user.roles.map((role) => <code key={role}>{role}</code>)
                    : <span>未分配角色</span>}
                </div>
                <div className="user-status-cell">
                  <Status tone={toStatusTone(user.enabled ? "ENABLED" : "DISABLED")}>
                    {user.enabled ? "已启用" : "已停用"}
                  </Status>
                  {user.mustChangePassword ? <small>首次登录须改密</small> : null}
                </div>
                <div className="row-actions">
                  <button onClick={() => openDialog({ mode: "edit", user })}>编辑</button>
                  <button
                    disabled={isSelf}
                    title={isSelf ? "不能通过此端点重置自己的密码；请使用“修改密码”。" : undefined}
                    onClick={() => openDialog({ mode: "reset", user })}
                  >
                    重置密码
                  </button>
                </div>
              </article>
            );
          })}
        </section>
      ) : null}
      <div className="model-footnotes">
        <div><b>会话失效</b><p>修改角色或启停后，该用户的所有现有会话立即失效，需要重新登录。</p></div>
        <div><b>首次改密</b><p>新建账号与重置密码都强制首次登录修改密码，且绝不记录密码本身。</p></div>
        <div><b>自我保护</b><p>管理员不能停用自己、移除自己的 ADMIN 角色，也不能通过此端点重置自己的密码。</p></div>
      </div>
      {dialog?.mode === "create" ? (
        <CreateUserDialog
          saving={saving}
          formError={formError}
          formFieldErrors={formFieldErrors}
          onClose={closeDialog}
          onSubmit={(event) => void submitCreate(event)}
        />
      ) : null}
      {dialog?.mode === "edit" ? (
        <EditUserDialog
          user={dialog.user}
          currentUserId={currentUser?.id ?? null}
          saving={saving}
          formError={formError}
          formFieldErrors={formFieldErrors}
          onClose={closeDialog}
          onSubmit={(changed) => void submitEdit(changed)}
        />
      ) : null}
      {dialog?.mode === "reset" ? (
        <ResetPasswordDialog
          user={dialog.user}
          saving={saving}
          formError={formError}
          onClose={closeDialog}
          onSubmit={(event) => void submitReset(event)}
        />
      ) : null}
    </div>
  );
}
