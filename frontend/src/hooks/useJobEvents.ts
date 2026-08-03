import { useCallback, useEffect, useRef, useState } from "react";
import type { JobEvent } from "../domain";

const eventTypes: JobEvent["type"][] = ["stage-started", "progress", "preview", "warning", "completed", "failed"];

const demoSequence: Omit<JobEvent, "eventId" | "sequence" | "jobId" | "traceId" | "createdAt">[] = [
  { type: "stage-started", stage: "material-lock", percent: 8, messageCode: "MATERIAL_SET_LOCKED", message: "固定本轮素材、模型与 Skill 版本" },
  { type: "progress", stage: "map", percent: 34, messageCode: "MAP_EXTRACTION_PROGRESS", message: "按来源 Chunk 提取候选规则与例外" },
  { type: "preview", stage: "reduce", percent: 62, messageCode: "REDUCE_PREVIEW_READY", message: "合并重复规则，发现 2 处口径差异" },
  { type: "progress", stage: "validate", percent: 84, messageCode: "KNOWLEDGE_IR_VALIDATING", message: "校验来源引用与规则一致性" },
  { type: "completed", stage: "persist", percent: 100, messageCode: "REVISION_PERSISTED", message: "已保存不可变 Revision rev-08" },
];

function parseJobEvent(jobId: string, fallbackType: JobEvent["type"], message: MessageEvent<string>): JobEvent | null {
  try {
    const value = JSON.parse(message.data) as Record<string, unknown>;
    const type = typeof value.type === "string" && eventTypes.includes(value.type as JobEvent["type"])
      ? value.type as JobEvent["type"]
      : fallbackType;
    const percent = typeof value.percent === "number" ? Math.min(100, Math.max(0, value.percent)) : 0;
    return {
      eventId: typeof value.eventId === "string" ? value.eventId : message.lastEventId,
      sequence: typeof value.sequence === "number" ? value.sequence : 0,
      jobId: typeof value.jobId === "string" ? value.jobId : jobId,
      type,
      stage: typeof value.stage === "string" ? value.stage : "unknown",
      percent,
      messageCode: typeof value.messageCode === "string" ? value.messageCode : "JOB_EVENT",
      message: typeof value.message === "string" ? value.message : "任务状态已更新",
      traceId: typeof value.traceId === "string" ? value.traceId : "",
      createdAt: typeof value.timestamp === "string"
        ? value.timestamp
        : typeof value.createdAt === "string" ? value.createdAt : new Date().toISOString(),
    };
  } catch {
    return null;
  }
}

interface UseJobEventsOptions {
  jobId: string | null;
  demo?: boolean;
}

export function useJobEvents({ jobId, demo = false }: UseJobEventsOptions) {
  const [events, setEvents] = useState<JobEvent[]>([]);
  const [connection, setConnection] = useState<"idle" | "connecting" | "open" | "closed">("idle");
  const demoTimer = useRef<number | null>(null);

  const reset = useCallback(() => {
    setEvents([]);
    setConnection("idle");
    if (demoTimer.current !== null) window.clearInterval(demoTimer.current);
    demoTimer.current = null;
  }, []);

  useEffect(() => {
    reset();
    if (!jobId) return;

    setConnection("connecting");
    if (demo) {
      let cursor = 0;
      setConnection("open");
      demoTimer.current = window.setInterval(() => {
        const next = demoSequence[cursor];
        if (!next) {
          if (demoTimer.current !== null) window.clearInterval(demoTimer.current);
          demoTimer.current = null;
          setConnection("closed");
          return;
        }
        setEvents((current) => [
          ...current,
          {
            ...next,
            eventId: `${jobId}-${cursor + 1}`,
            sequence: cursor + 1,
            jobId,
            traceId: "demo-trace",
            createdAt: new Date().toISOString(),
          },
        ]);
        cursor += 1;
      }, 720);
      return () => {
        if (demoTimer.current !== null) window.clearInterval(demoTimer.current);
        demoTimer.current = null;
      };
    }

    const source = new EventSource(`/api/v1/jobs/${encodeURIComponent(jobId)}/events`, { withCredentials: true });
    source.onopen = () => setConnection("open");
    const consume = (fallbackType: JobEvent["type"]) => (event: Event) => {
      const parsed = parseJobEvent(jobId, fallbackType, event as MessageEvent<string>);
      if (parsed) setEvents((current) => [...current.slice(-499), parsed]);
    };
    const listeners = eventTypes.map((type) => [type, consume(type)] as const);
    listeners.forEach(([type, listener]) => source.addEventListener(type, listener));
    source.onmessage = consume("progress");
    source.onerror = () => {
      setConnection("connecting");
    };
    return () => {
      listeners.forEach(([type, listener]) => source.removeEventListener(type, listener));
      source.close();
    };
  }, [demo, jobId, reset]);

  return { events, connection, reset };
}
