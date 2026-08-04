import { Shell } from "./components/Shell";
import { usePathname } from "./lib/navigation";
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
  if (pathname === "/login") return <LoginPage
    onLogin={(mustChangePassword) => navigate(mustChangePassword ? "/change-password" : "/")}
    onDemo={() => navigate("/")}
  />;
  if (pathname === "/change-password") return <ChangePasswordPage onChanged={() => navigate("/login")} />;

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

  return <Shell pathname={pathname} onNavigate={navigate}>{page}</Shell>;
}
