import { useEffect, useRef, useState } from "react";
import { Shell } from "./components/Shell";
import { usePathname } from "./lib/navigation";
import { AUTHENTICATION_REQUIRED_EVENT, getCurrentUser } from "./lib/api";
import type { AuthenticatedUser } from "./lib/api";
import { AgentsPage } from "./pages/AgentsPage";
import { AuditPage } from "./pages/AuditPage";
import { DashboardPage } from "./pages/DashboardPage";
import { LoginPage } from "./pages/LoginPage";
import { ChangePasswordPage } from "./pages/ChangePasswordPage";
import { ModelsPage } from "./pages/ModelsPage";
import { ScenePage } from "./pages/ScenePage";
import { SkillsPage } from "./pages/SkillsPage";
import { UsersPage } from "./pages/UsersPage";
import { ExplorationPage } from "./pages/ExplorationPage";
import { Button, Glyph } from "./components/Ui";

function NotFound({ onNavigate }: { onNavigate: (href: string) => void }) {
  return <div className="page not-found"><span>404</span><h1>没有找到这个页面</h1><p>地址可能已变化，或对应场景已被删除。返回工作台查看场景列表。</p><Button className="button--primary" onClick={() => onNavigate("/")}><Glyph name="grid"/>返回工作台</Button></div>;
}

export default function App() {
  const { pathname, navigate } = usePathname();
  const [user, setUser] = useState<AuthenticatedUser | null | undefined>(undefined);
  const returnTo = useRef(
    pathname === "/login" || pathname === "/change-password"
      ? "/"
      : `${window.location.pathname}${window.location.search}${window.location.hash}`,
  );

  useEffect(() => {
    const requireAuthentication = () => {
      if (window.location.pathname !== "/login") {
        const target = `${window.location.pathname}${window.location.search}${window.location.hash}`;
        if (window.location.pathname !== "/change-password") returnTo.current = target;
        setUser(null);
        navigate("/login", true);
      }
    };
    window.addEventListener(AUTHENTICATION_REQUIRED_EVENT, requireAuthentication);
    return () => window.removeEventListener(AUTHENTICATION_REQUIRED_EVENT, requireAuthentication);
  }, [navigate]);

  useEffect(() => {
    if (pathname === "/login" || user !== undefined) return;
    let cancelled = false;
    getCurrentUser()
      .then((loaded) => { if (!cancelled) setUser(loaded); })
      .catch(() => { if (!cancelled) setUser(null); });
    return () => { cancelled = true; };
  }, [pathname, user]);

  useEffect(() => {
    if (pathname === "/login" || user === undefined) return;
    if (user === null) {
      navigate("/login", true);
      return;
    }
    if (user.mustChangePassword && pathname !== "/change-password") {
      navigate("/change-password", true);
      return;
    }
    if (!user.mustChangePassword && pathname === "/change-password") {
      navigate(returnTo.current, true);
    }
  }, [navigate, pathname, user]);

  if (pathname === "/login") return <LoginPage
    onLogin={(loaded) => {
      setUser(loaded);
      navigate(loaded.mustChangePassword ? "/change-password" : returnTo.current, true);
    }}
  />;

  if (user === undefined) {
    return <main className="auth-loading" role="status">正在验证会话…</main>;
  }
  if (user === null) return null;
  if (user.mustChangePassword && pathname !== "/change-password") return null;
  if (!user.mustChangePassword && pathname === "/change-password") return null;
  if (pathname === "/change-password") return <ChangePasswordPage onChanged={() => {
    setUser(null);
    navigate("/login", true);
  }} />;

  let page;
  if (pathname === "/") page = <DashboardPage onNavigate={navigate}/>;
  else if (pathname === "/explore") page = <ExplorationPage onNavigate={navigate}/>;
  else if (pathname.startsWith("/scenes/")) {
    const sceneId = pathname.split("/")[2];
    page = sceneId
      ? <ScenePage sceneId={sceneId} onNavigate={navigate}/>
      : <NotFound onNavigate={navigate}/>;
  }
  else if (pathname === "/agents") page = <AgentsPage/>;
  else if (pathname === "/skills") page = <SkillsPage/>;
  else if (pathname === "/models") page = <ModelsPage/>;
  else if (pathname === "/users") page = <UsersPage/>;
  else if (pathname === "/audit") page = <AuditPage/>;
  else page = <NotFound onNavigate={navigate}/>;

  return <Shell pathname={pathname} onNavigate={navigate} user={user}>{page}</Shell>;
}
