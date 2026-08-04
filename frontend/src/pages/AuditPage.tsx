import { useCallback, useEffect, useMemo, useState } from "react";
import { Button, EmptyState, Glyph, PageHeader } from "../components/Ui";
import { ApiError, listAuditEvents } from "../lib/api";
import type { AuditEvent } from "../lib/api";
import { safeAuditDetails } from "../domain";

const PAGE_SIZE = 50;

function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
}

function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

function csvCell(value: string): string {
  if (/[",\n\r]/.test(value)) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

export function AuditPage() {
  const [events, setEvents] = useState<AuditEvent[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [actionFilter, setActionFilter] = useState("");
  const [traceFilter, setTraceFilter] = useState("");

  const loadEvents = useCallback(async (targetPage: number) => {
    setLoadError(null);
    try {
      const result = await listAuditEvents(targetPage, PAGE_SIZE);
      setEvents(result);
      setPage(targetPage);
    } catch (reason) {
      setEvents(null);
      if (reason instanceof ApiError && (reason.status === 403 || reason.code === "forbidden")) {
        setLoadError("仅 ADMIN 可查看审计记录。");
      } else {
        setLoadError(reason instanceof ApiError ? reason.message : "无法连接 API，请稍后重试。");
      }
    }
  }, []);

  useEffect(() => {
    void loadEvents(0);
  }, [loadEvents]);

  const visible = useMemo(() => {
    if (events === null) return [];
    const action = actionFilter.trim().toLowerCase();
    const trace = traceFilter.trim().toLowerCase();
    return events.filter((event) =>
      (!action || event.action.toLowerCase().includes(action))
      && (!trace || (event.traceId ?? "").toLowerCase().includes(trace)));
  }, [events, actionFilter, traceFilter]);

  const exportCsv = () => {
    if (visible.length === 0) return;
    const header = ["id", "occurredAt", "action", "targetType", "targetId", "actorId", "traceId", "details"];
    const rows = visible.map((event) => [
      event.id, event.occurredAt, event.action, event.targetType, event.targetId, event.actorId,
      event.traceId ?? "",
      safeAuditDetails(event.detailsJson).map((detail) => `${detail.key}=${detail.value}`).join(" "),
    ]);
    const csv = [header, ...rows].map((row) => row.map(csvCell).join(",")).join("\n");
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `audit-page-${page + 1}.csv`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  };

  const canGoPrevious = page > 0;
  const canGoNext = events !== null && events.length === PAGE_SIZE;

  return (
    <div className="page audit-page">
      <PageHeader
        eyebrow="治理 / 审计"
        title="从操作到内容版本的证据链"
        description="只记录必要元数据、Revision/hash、Job 和 trace ID；正文、Prompt、模型响应与凭证不会进入审计日志。仅 ADMIN 可查看。"
        actions={<Button className="button--quiet" onClick={exportCsv} disabled={visible.length === 0}><Glyph name="download" />导出筛选结果</Button>}
      />
      {loadError ? (
        <div className="load-error" role="alert">
          <Glyph name="warning" size={16} />
          <div>
            <b>无法加载审计记录</b>
            <span>{loadError}</span>
          </div>
          <Button className="button--quiet button--small" onClick={() => void loadEvents(page)}>重试</Button>
        </div>
      ) : null}
      <div className="audit-filters">
        <label><span>动作（本地过滤当前页）</span><input placeholder="例如 RELEASE_PUBLISHED" value={actionFilter}
          onChange={(event) => setActionFilter(event.currentTarget.value)} /></label>
        <label><span>追踪 ID（本地过滤当前页）</span><input placeholder="traceId 片段" value={traceFilter}
          onChange={(event) => setTraceFilter(event.currentTarget.value)} /></label>
        <div className="audit-filters__paging">
          <Button className="button--quiet button--small" disabled={!canGoPrevious}
            onClick={() => void loadEvents(page - 1)}>上一页</Button>
          <span>第 {page + 1} 页</span>
          <Button className="button--quiet button--small" disabled={!canGoNext}
            onClick={() => void loadEvents(page + 1)}>下一页</Button>
        </div>
      </div>
      {events === null && !loadError ? (
        <div className="model-loading" aria-busy="true">正在加载审计记录…</div>
      ) : null}
      {events !== null && visible.length === 0 ? (
        <EmptyState title={events.length === 0 ? "没有审计记录" : "没有匹配的过滤结果"}
          detail={events.length === 0 ? "当前没有任何审计事件。" : "调整动作或追踪 ID 过滤条件。"} />
      ) : null}
      {events !== null && visible.length > 0 ? (
        <section className="audit-log" aria-label="审计记录">
          <div className="audit-log__head"><span>时间</span><span>Actor ID</span><span>动作</span><span>对象 / 元数据</span><span>Trace</span></div>
          {visible.map((event) => {
            const details = safeAuditDetails(event.detailsJson);
            return (
              <article key={event.id}>
                <time>{formatDateTime(event.occurredAt)}</time>
                <div className="audit-actor" title={event.actorId}>
                  <span className="actor-mark">{shortId(event.actorId).slice(0, 1)}</span>
                  <div><b>Actor {shortId(event.actorId)}</b><small>actorId</small></div>
                </div>
                <strong>{event.action}</strong>
                <div>
                  <b>{event.targetType}</b>
                  <code>{shortId(event.targetId)}</code>
                  {details.length > 0 ? (
                    <span className="audit-details">{details.map((detail) => (
                      <code key={detail.key} title={`${detail.key}=${detail.value}`}>{detail.key}={detail.value}</code>
                    ))}</span>
                  ) : null}
                </div>
                <code>{event.traceId ?? ""}</code>
              </article>
            );
          })}
        </section>
      ) : null}
      <div className="retention-note"><Glyph name="lock" size={16} /><span>AuditEvent 不可修改；正式环境的保留期限和归档策略由部署配置控制。</span></div>
    </div>
  );
}
