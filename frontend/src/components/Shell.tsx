import type { PropsWithChildren } from "react";
import type { GlyphName } from "./Ui";
import { DemoNotice, Glyph } from "./Ui";

interface NavItem {
  href: string;
  label: string;
  icon: GlyphName;
}

const navItems: NavItem[] = [
  { href: "/", label: "工作台", icon: "grid" },
  { href: "/scenes/corporate-loan-classification", label: "场景流程", icon: "scene" },
  { href: "/agents", label: "智能体", icon: "bot" },
  { href: "/skills", label: "Skill", icon: "skill" },
  { href: "/models", label: "模型", icon: "model" },
  { href: "/users", label: "用户", icon: "users" },
  { href: "/audit", label: "审计", icon: "audit" },
];

export function Shell({ pathname, onNavigate, children }: PropsWithChildren<{ pathname: string; onNavigate: (href: string) => void }>) {
  const isActive = (href: string) => href === "/" ? pathname === "/" : pathname.startsWith(href.split("/").slice(0, 2).join("/"));

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <aside className="side-rail" aria-label="主导航">
        <button className="brand-mark" aria-label="返回工作台" onClick={() => onNavigate("/")}>
          <span>K</span><i />
        </button>
        <nav>
          {navItems.map((item) => (
            <a
              key={item.href}
              href={item.href}
              className={isActive(item.href) ? "active" : ""}
              aria-current={isActive(item.href) ? "page" : undefined}
              onClick={(event) => { event.preventDefault(); onNavigate(item.href); }}
            >
              <Glyph name={item.icon} size={20}/>
              <span>{item.label}</span>
            </a>
          ))}
        </nav>
        <button className="profile-chip" aria-label="当前用户：曹征">
          <span>曹</span><i className="presence" />
        </button>
      </aside>

      <div className="shell-body">
        <header className="topbar">
          <div className="wordmark">
            <strong>知识萃取智能体工作台</strong>
            <span>KNOWLEDGE DISTILLATION</span>
          </div>
          <DemoNotice compact />
          <div className="topbar__actions">
            <label className="global-search">
              <span className="sr-only">搜索</span>
              <Glyph name="search" size={16}/>
              <input type="search" placeholder="搜索场景、规则、来源…" />
              <kbd>⌘ K</kbd>
            </label>
            <button className="icon-button" aria-label="通知"><Glyph name="bell" /></button>
            <button className="avatar-button" aria-label="用户菜单">曹征 <span>管理员</span></button>
          </div>
        </header>
        <main id="main-content" className="main-content" tabIndex={-1}>{children}</main>
      </div>
    </div>
  );
}
