import type { ButtonHTMLAttributes, PropsWithChildren, ReactNode } from "react";
import type { Tone } from "../domain";

export type GlyphName =
  | "grid"
  | "scene"
  | "bot"
  | "skill"
  | "model"
  | "users"
  | "audit"
  | "search"
  | "bell"
  | "plus"
  | "file"
  | "play"
  | "download"
  | "check"
  | "warning"
  | "lock"
  | "chevron"
  | "logout"
  | "menu"
  | "close"
  | "history"
  | "link";

const paths: Record<GlyphName, ReactNode> = {
  grid: <><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></>,
  scene: <><path d="M4 4h16v5H4zM4 13h7v7H4zM15 13h5v7h-5z"/><path d="M12 6.5h2M7.5 9v4"/></>,
  bot: <><rect x="4" y="7" width="16" height="12" rx="3"/><path d="M12 3v4M8 12h.01M16 12h.01M8 16h8"/></>,
  skill: <><path d="M8 3h8l5 5v8l-5 5H8l-5-5V8z"/><path d="M9 8l-4 4 4 4M15 8l4 4-4 4"/></>,
  model: <><rect x="5" y="5" width="14" height="14" rx="3"/><rect x="9" y="9" width="6" height="6" rx="1"/><path d="M8 2v3M16 2v3M8 19v3M16 19v3M2 8h3M2 16h3M19 8h3M19 16h3"/></>,
  users: <><circle cx="9" cy="8" r="3"/><path d="M3 20v-1a6 6 0 0112 0v1M16 5a3 3 0 010 6M18 14a5 5 0 013 5v1"/></>,
  audit: <><path d="M6 3h12v18H6zM9 3v3h6V3M9 11h6M9 15h5"/><path d="M9 8h.01"/></>,
  search: <><circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/></>,
  bell: <><path d="M5 17h14l-2-3V9a5 5 0 00-10 0v5zM10 20h4"/></>,
  plus: <path d="M12 5v14M5 12h14"/>,
  file: <><path d="M6 2h8l4 4v16H6zM14 2v5h5M9 12h6M9 16h6"/></>,
  play: <path d="M8 5l11 7-11 7z"/>,
  download: <><path d="M12 3v12M7 10l5 5 5-5M4 19h16"/></>,
  check: <path d="M5 12l4 4L19 6"/>,
  warning: <><path d="M12 3l10 18H2zM12 9v5M12 18h.01"/></>,
  lock: <><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 018 0v3"/></>,
  chevron: <path d="M9 5l7 7-7 7"/>,
  logout: <><path d="M10 4H5v16h5M14 8l4 4-4 4M8 12h10"/></>,
  menu: <><path d="M4 7h16M4 12h16M4 17h16"/></>,
  close: <path d="M6 6l12 12M18 6L6 18"/>,
  history: <><path d="M4 8V3M4 3h5M4 4a9 9 0 101-1M12 7v5l3 2"/></>,
  link: <><path d="M10 13a5 5 0 007 0l2-2a5 5 0 00-7-7l-1 1M14 11a5 5 0 00-7 0l-2 2a5 5 0 007 7l1-1"/></>,
};

export function Glyph({ name, size = 18 }: { name: GlyphName; size?: number }) {
  return (
    <svg className="glyph" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

export function Button({ className = "", children, ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button className={`button ${className}`} {...props}>{children}</button>;
}

export function Status({ children, tone = "neutral" }: PropsWithChildren<{ tone?: Tone }>) {
  return <span className={`status status--${tone}`}><span className="status__dot" />{children}</span>;
}

export function PageHeader({ eyebrow, title, description, actions }: { eyebrow?: string; title: string; description: string; actions?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        {eyebrow ? <div className="eyebrow">{eyebrow}</div> : null}
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {actions ? <div className="page-header__actions">{actions}</div> : null}
    </header>
  );
}

export function DemoNotice({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`demo-notice ${compact ? "demo-notice--compact" : ""}`} role="note">
      <span className="demo-notice__signal" />
      <strong>离线演示数据</strong>
      {!compact ? <span>当前页面不连接后端；交互用于验证信息架构与状态流转。</span> : null}
    </div>
  );
}

export function EmptyState({ title, detail }: { title: string; detail: string }) {
  return <div className="empty-state"><Glyph name="file" size={24}/><strong>{title}</strong><span>{detail}</span></div>;
}
