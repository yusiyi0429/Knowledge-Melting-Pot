import { useEffect, useRef, useState } from "react";
import type { PropsWithChildren } from "react";
import type { GlyphName } from "./Ui";
import { Glyph } from "./Ui";
import { getNotificationInbox, markAllNotificationsRead, markNotificationRead, searchWorkbench } from "../lib/api";
import type { AuthenticatedUser, NotificationInbox, SearchResult } from "../lib/api";

interface NavItem {
  href: string;
  label: string;
  icon: GlyphName;
}

const navItems: NavItem[] = [
  { href: "/", label: "工作台", icon: "grid" },
  { href: "/explore", label: "探索", icon: "search" },
  { href: "/agents", label: "智能体", icon: "bot" },
  { href: "/skills", label: "Skill", icon: "skill" },
  { href: "/models", label: "模型", icon: "model" },
  { href: "/users", label: "用户", icon: "users" },
  { href: "/audit", label: "审计", icon: "audit" },
];

function roleLabel(user: AuthenticatedUser | null | undefined): string | null {
  if (!user) return null;
  if (user.roles.includes("ADMIN")) return "管理员";
  if (user.roles.includes("PUBLISHER")) return "发布";
  if (user.roles.includes("OPERATOR")) return "运营";
  return "无角色";
}

export function Shell({ pathname, onNavigate, user, children }: PropsWithChildren<{ pathname: string; onNavigate: (href: string) => void; user: AuthenticatedUser }>) {
  const isActive = (href: string) => href === "/" ? pathname === "/" : pathname.startsWith(href.split("/").slice(0, 2).join("/"));
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [searching, setSearching] = useState(false);
  const searchInput = useRef<HTMLInputElement>(null);
  const [notificationOpen, setNotificationOpen] = useState(false);
  const [inbox, setInbox] = useState<NotificationInbox>({ unreadCount: 0, items: [] });

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setSearchOpen(true);
        window.setTimeout(() => searchInput.current?.focus(), 0);
      }
      if (event.key === "Escape") {
        setSearchOpen(false);
        setNotificationOpen(false);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  useEffect(() => {
    const query = searchQuery.trim();
    if (query.length < 2) {
      setSearchResults([]);
      setSearching(false);
      return;
    }
    let cancelled = false;
    setSearching(true);
    const timer = window.setTimeout(() => {
      searchWorkbench(query)
        .then((items) => { if (!cancelled) setSearchResults(items); })
        .catch(() => { if (!cancelled) setSearchResults([]); })
        .finally(() => { if (!cancelled) setSearching(false); });
    }, 260);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [searchQuery]);

  useEffect(() => {
    let cancelled = false;
    const load = () => getNotificationInbox().then((value) => { if (!cancelled) setInbox(value); }).catch(() => undefined);
    void load();
    const timer = window.setInterval(() => void load(), 30_000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, []);

  const navigateToResult = (result: SearchResult) => {
    setSearchOpen(false);
    setSearchQuery("");
    onNavigate(`/scenes/${result.sceneId}${result.subSceneId ? `?subSceneId=${result.subSceneId}` : ""}`);
  };

  const readNotification = async (id: string) => {
    const item = inbox.items.find((value) => value.id === id);
    if (!item || item.read) return;
    try {
      await markNotificationRead(id);
      setInbox((current) => ({
        unreadCount: Math.max(0, current.unreadCount - 1),
        items: current.items.map((value) => value.id === id ? { ...value, read: true, readAt: new Date().toISOString() } : value),
      }));
    } catch { /* The next poll will reconcile the inbox. */ }
  };

  const identity = user.displayName;
  const avatarLabel = `当前用户：${user.displayName}`;
  const label = roleLabel(user);

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
        <button className="profile-chip" aria-label={avatarLabel}>
          <span>{identity.slice(0, 1)}</span><i className="presence" />
        </button>
      </aside>

      <div className="shell-body">
        <header className="topbar">
          <div className="wordmark">
            <strong>知识萃取智能体工作台</strong>
            <span>KNOWLEDGE DISTILLATION</span>
          </div>
          <div className="topbar__actions">
            <label className="global-search">
              <span className="sr-only">搜索</span>
              <Glyph name="search" size={16}/>
              <input ref={searchInput} type="search" placeholder="搜索场景、规则、来源…" value={searchQuery}
                aria-expanded={searchOpen} aria-controls="global-search-results"
                onFocus={() => setSearchOpen(true)} onChange={(event) => setSearchQuery(event.currentTarget.value)} />
              <kbd>⌘ K</kbd>
            </label>
            {searchOpen ? <div id="global-search-results" className="search-palette" role="dialog" aria-label="全局搜索">
              <header><b>跨场景搜索</b><button onClick={() => setSearchOpen(false)} aria-label="关闭搜索"><Glyph name="close" size={14}/></button></header>
              {searchQuery.trim().length < 2 ? <p>输入至少 2 个字符，搜索场景、当前规则文档与来源文件。</p> : null}
              {searching ? <p>正在检索…</p> : null}
              {!searching && searchQuery.trim().length >= 2 && searchResults.length === 0 ? <p>没有找到匹配内容。</p> : null}
              <div className="search-palette__results">
                {searchResults.map((result) => <button key={`${result.type}-${result.resourceId}`} onClick={() => navigateToResult(result)}>
                  <span>{result.type === "SCENE" ? "场景" : result.type === "RULE" ? "规则" : "来源"}</span>
                  <div><b>{result.title}</b><small>{result.excerpt || "打开对应场景查看详情"}</small></div><Glyph name="chevron" size={14}/>
                </button>)}
              </div>
            </div> : null}
            <div className="notification-anchor">
              <button className="icon-button" aria-label={`通知${inbox.unreadCount ? `，${inbox.unreadCount} 条未读` : ""}`}
                aria-expanded={notificationOpen} onClick={() => setNotificationOpen((value) => !value)}>
                <Glyph name="bell" />{inbox.unreadCount ? <span className="notification-count">{Math.min(99, inbox.unreadCount)}</span> : null}
              </button>
              {notificationOpen ? <div className="notification-panel" role="dialog" aria-label="任务通知">
                <header><div><b>任务通知</b><span>{inbox.unreadCount} 条未读</span></div>
                  <button disabled={inbox.unreadCount === 0} onClick={() => void markAllNotificationsRead().then(() =>
                    setInbox((current) => ({ unreadCount: 0, items: current.items.map((item) => ({ ...item, read: true, readAt: item.readAt ?? new Date().toISOString() })) }))
                  )}>全部已读</button></header>
                <div className="notification-panel__list">
                  {inbox.items.length === 0 ? <p>后台任务完成后会在这里留下通知。</p> : inbox.items.map((item) =>
                    <button key={item.id} className={item.read ? "read" : ""} onClick={() => void readNotification(item.id)}>
                      <span className="notification-signal"/><div><b>{item.title}</b><small>{item.message}</small><time>{new Date(item.createdAt).toLocaleString("zh-CN")}</time></div>
                    </button>)}
                </div>
              </div> : null}
            </div>
            <button className="avatar-button" aria-label={avatarLabel}>
              {identity}{label ? <span>{label}</span> : null}
            </button>
          </div>
        </header>
        <main id="main-content" className="main-content" tabIndex={-1}>{children}</main>
      </div>
    </div>
  );
}
