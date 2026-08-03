import { FormEvent, useState } from "react";
import { Button, Glyph } from "../components/Ui";
import { ApiError, login } from "../lib/api";

export function LoginPage({
  onLogin,
  onDemo,
}: {
  onLogin: (mustChangePassword: boolean) => void;
  onDemo: () => void;
}) {
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    const form = new FormData(event.currentTarget);
    const username = String(form.get("username") ?? "").trim();
    const password = String(form.get("password") ?? "");
    if (!username || !password) {
      setError("请输入用户名和密码。");
      return;
    }
    setBusy(true);
    try {
      const user = await login(username, password);
      onLogin(user.mustChangePassword);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "无法连接 API，请稍后重试或进入离线演示。");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="login-page">
      <section className="login-context" aria-label="产品说明">
        <div className="login-context__brand"><span>K</span><strong>知识萃取智能体工作台</strong></div>
        <div className="login-context__body">
          <div className="eyebrow eyebrow--light">KNOWLEDGE LINEAGE</div>
          <h1>让每条知识，<br/>都有来处和版本。</h1>
          <p>把制度、案例和专家经验萃取为可执行、可审计、可持续迭代的知识资产。</p>
          <ol className="login-lineage" aria-label="产品工作流">
            <li><span>01</span><b>固定素材</b><small>原始文件与来源定位</small></li>
            <li><span>02</span><b>萃取对齐</b><small>Revision 与人工确认</small></li>
            <li><span>03</span><b>生成资产</b><small>规则、流程、Skill、QA、评测集</small></li>
            <li><span>04</span><b>发布快照</b><small>不可变 Manifest</small></li>
          </ol>
        </div>
        <small className="login-context__foot">单组织私有化部署 · 所有操作留痕</small>
      </section>
      <section className="login-panel">
        <div className="login-card">
          <div className="demo-notice" role="note"><span className="demo-notice__signal"/><strong>在线登录</strong><span>凭据只发送到同源 API，不写入浏览器存储。</span></div>
          <div className="login-card__head">
            <span className="login-lock"><Glyph name="lock" size={18}/></span>
            <div><h2>登录工作台</h2><p>账号由管理员创建，不开放自助注册。</p></div>
          </div>
          <form onSubmit={submit} noValidate>
            <label className="field">
              <span>用户名</span>
              <input name="username" autoComplete="username" placeholder="请输入管理员创建的账号" />
            </label>
            <label className="field">
              <span>密码</span>
              <div className="password-field">
                <input name="password" type={showPassword ? "text" : "password"} autoComplete="current-password" placeholder="请输入密码" />
                <button type="button" onClick={() => setShowPassword((value) => !value)}>{showPassword ? "隐藏" : "显示"}</button>
              </div>
            </label>
            {error ? <div className="form-error" role="alert">{error}</div> : null}
            <Button className="button--primary button--wide" type="submit" disabled={busy}>{busy ? "正在登录…" : "进入工作台"} <Glyph name="chevron" size={16}/></Button>
          </form>
          <div className="login-demo-action"><span>尚未启动后端？</span><Button className="button--quiet" type="button" onClick={onDemo}>进入离线演示</Button></div>
          <div className="login-card__hint"><Glyph name="lock" size={14}/> 正式环境使用 HttpOnly 会话 Cookie 与 CSRF 防护。</div>
        </div>
      </section>
    </main>
  );
}
