import { useState } from "react";
import { Button, DemoNotice, Glyph, PageHeader, Status } from "../components/Ui";
import { audits } from "../fixtures";

export function AuditPage() {
  const [actorType, setActorType] = useState<"ALL" | "USER" | "SYSTEM" | "AGENT">("ALL");
  const visible = audits.filter((audit) => actorType === "ALL" || audit.actorType === actorType);
  return (
    <div className="page audit-page">
      <DemoNotice />
      <PageHeader eyebrow="治理 / 审计" title="从操作到内容版本的证据链" description="只记录必要元数据、Revision/hash、Job 和 trace ID；正文、Prompt、模型响应与凭证不会进入审计日志。" actions={<Button className="button--quiet"><Glyph name="download"/>导出筛选结果</Button>} />
      <div className="audit-filters"><label><span>Actor 类型</span><select value={actorType} onChange={(event) => setActorType(event.target.value as typeof actorType)}><option value="ALL">全部 Actor</option><option value="USER">USER</option><option value="SYSTEM">SYSTEM</option><option value="AGENT">AGENT</option></select></label><label><span>追踪 ID</span><input placeholder="例如 tr-8f0d2a"/></label><label><span>时间范围</span><select><option>最近 7 天</option><option>最近 30 天</option></select></label><Button className="button--primary">应用筛选</Button></div>
      <section className="audit-log" aria-label="审计记录">
        <div className="audit-log__head"><span>时间</span><span>Actor</span><span>动作</span><span>对象 / Revision</span><span>Trace</span></div>
        {visible.map((record) => <article key={record.id}><time>{record.at}</time><div className="audit-actor"><span className={`actor-mark actor-mark--${record.actorType.toLowerCase()}`}>{record.actorType.slice(0, 1)}</span><div><b>{record.actor}</b><Status tone={record.actorType === "USER" ? "info" : record.actorType === "AGENT" ? "purple" : "neutral"}>{record.actorType}</Status></div></div><strong>{record.action}</strong><div><b>{record.target}</b><code>{record.revision}</code></div><code>{record.traceId}</code></article>)}
      </section>
      <div className="retention-note"><Glyph name="lock" size={16}/><span>AuditEvent 不可修改；正式环境的保留期限和归档策略由部署配置控制。</span></div>
    </div>
  );
}
