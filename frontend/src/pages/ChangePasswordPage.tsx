import { FormEvent, useState } from "react";
import { Button, Glyph } from "../components/Ui";
import { ApiError, changePassword } from "../lib/api";

export function ChangePasswordPage({ onChanged }: { onChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    const form = new FormData(event.currentTarget);
    const currentPassword = String(form.get("currentPassword") ?? "");
    const newPassword = String(form.get("newPassword") ?? "");
    const confirmation = String(form.get("confirmation") ?? "");
    if (!currentPassword || !newPassword) {
      setError("请填写当前密码和新密码。");
      return;
    }
    if (newPassword !== confirmation) {
      setError("两次输入的新密码不一致。");
      return;
    }
    if (newPassword.length < 12) {
      setError("新密码至少需要 12 个字符。");
      return;
    }
    setBusy(true);
    try {
      await changePassword(currentPassword, newPassword);
      onChanged();
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "密码修改失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="login-page">
      <section className="login-context" aria-label="密码安全说明">
        <div className="login-context__brand"><span>K</span><strong>知识萃取智能体工作台</strong></div>
        <div className="login-context__body">
          <div className="eyebrow eyebrow--light">FIRST SIGN-IN</div>
          <h1>先固定账号边界，<br/>再进入知识工作流。</h1>
          <p>初始密码只能用于首次登录。修改成功后，当前会话会被撤销，需要使用新密码重新登录。</p>
        </div>
        <small className="login-context__foot">密码不会写入日志或浏览器存储</small>
      </section>
      <section className="login-panel">
        <div className="login-card">
          <div className="login-card__head">
            <span className="login-lock"><Glyph name="lock" size={18}/></span>
            <div><h2>修改初始密码</h2><p>新密码至少 12 个字符。</p></div>
          </div>
          <form onSubmit={submit} noValidate>
            <label className="field"><span>当前密码</span><input name="currentPassword" type="password" autoComplete="current-password" /></label>
            <label className="field"><span>新密码</span><input name="newPassword" type="password" autoComplete="new-password" /></label>
            <label className="field"><span>确认新密码</span><input name="confirmation" type="password" autoComplete="new-password" /></label>
            {error ? <div className="form-error" role="alert">{error}</div> : null}
            <Button className="button--primary button--wide" type="submit" disabled={busy}>
              {busy ? "正在修改…" : "修改并重新登录"} <Glyph name="chevron" size={16}/>
            </Button>
          </form>
          <div className="login-card__hint"><Glyph name="lock" size={14}/> 成功后服务端会撤销该账号的全部现有会话。</div>
        </div>
      </section>
    </main>
  );
}
