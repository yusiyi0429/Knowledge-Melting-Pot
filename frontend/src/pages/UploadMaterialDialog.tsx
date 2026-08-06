import { FormEvent, useEffect, useRef, useState } from "react";
import { Button, Glyph, Status } from "../components/Ui";
import { abortUpload, completeUpload, createUploadIntent, getJob } from "../lib/api";
import type { Job, MaterialJobAccepted, MaterialPartition, UploadedPart } from "../lib/api";
import { mediaTypeForFile, partitionLabels, toStatusTone, validateMaterialFile } from "../domain";
import { sha256Hex } from "../lib/hashes";

type Phase = "form" | "hashing" | "uploading" | "completing" | "queued" | "cancelling" | "error";

const partitionOptions: Array<{
  value: MaterialPartition;
  title: string;
  description: string;
}> = [
  { value: "SOURCE", title: "业务素材", description: "参与知识萃取、检索和来源追溯" },
  { value: "LABELED_TRAIN", title: "标注训练", description: "用于生成和校验训练样本" },
  { value: "LABELED_HOLDOUT", title: "留出评测", description: "仅用于独立评测，不进入萃取流程" },
];

const JOB_STATUS_LABELS: Record<Job["status"], string> = {
  QUEUED: "等待安全校验",
  RUNNING: "安全校验中",
  SUCCEEDED: "校验完成",
  FAILED: "校验失败",
  CANCELLED: "校验已取消",
};

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function UploadMaterialDialog({
  roundId,
  subSceneId,
  explorationSessionId,
  onClose,
  onUploaded,
}: {
  roundId?: string;
  subSceneId?: string;
  explorationSessionId?: string;
  onClose: () => void;
  onUploaded: (job: MaterialJobAccepted) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const intentIdRef = useRef<string | null>(null);
  const cancellingRef = useRef(false);
  const abortControllerRef = useRef<AbortController | null>(null);

  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [partition, setPartition] = useState<MaterialPartition>("SOURCE");
  const [regulatorySource, setRegulatorySource] = useState(false);
  const [phase, setPhase] = useState<Phase>("form");
  const [sha256, setSha256] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [uploadedParts, setUploadedParts] = useState(0);
  const [totalParts, setTotalParts] = useState<number | null>(null);
  const [job, setJob] = useState<MaterialJobAccepted | null>(null);
  const [jobStatus, setJobStatus] = useState<Job | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (!node.open) node.showModal();
    return () => {
      if (node.open) node.close();
    };
  }, []);

  const selectFile = (next: File | null) => {
    setFileError(null);
    if (!next) {
      setFile(null);
      return;
    }
    const problem = validateMaterialFile(next);
    if (problem) {
      setFileError(problem);
      setFile(null);
      return;
    }
    setFile(next);
  };

  const abortCurrent = async () => {
    const intentId = intentIdRef.current;
    if (intentId) {
      intentIdRef.current = null;
      try {
        await abortUpload(intentId);
      } catch {
        // Best-effort; the server also expires stale intents.
      }
    }
  };

  const handleCancel = (event?: { preventDefault: () => void }) => {
    event?.preventDefault();
    if (cancellingRef.current) return;
    if (phase === "hashing" || phase === "uploading" || phase === "completing") {
      // Keep the dialog open, abort the in-flight PUT and the intent, then close.
      cancellingRef.current = true;
      setPhase("cancelling");
      abortControllerRef.current?.abort();
      void abortCurrent().then(onClose);
      return;
    }
    onClose();
  };

  const refreshJob = async () => {
    if (!job) return;
    try {
      setJobStatus(await getJob(job.jobId));
    } catch {
      setJobStatus(null);
    }
  };

  const startUpload = async () => {
    if (!file) return;
    setError(null);
    setPhase("hashing");
    let parts: UploadedPart[] = [];
    try {
      const buffer = await file.arrayBuffer();
      if (cancellingRef.current) return;
      const digest = await sha256Hex(buffer);
      if (cancellingRef.current) return;
      setSha256(digest);
      const mediaType = mediaTypeForFile(file.name);
      if (mediaType === null) throw new Error("不支持的文件类型。");

      setPhase("uploading");
      setProgress(0);
      const intent = await createUploadIntent({
        fileName: file.name,
        sizeBytes: file.size,
        mediaType,
        sha256: digest,
        roundId: roundId ?? null,
        explorationSessionId,
        subSceneIds: subSceneId ? [subSceneId] : [],
        partition: explorationSessionId ? "SOURCE" : partition,
        shareScope: "ROUND",
        regulatorySource: explorationSessionId || partition === "LABELED_HOLDOUT" ? false : regulatorySource,
      }, crypto.randomUUID());
      // Register the intent immediately so a cancel racing this await can still abort it.
      intentIdRef.current = intent.id;
      if (cancellingRef.current) {
        await abortCurrent();
        return;
      }

      if (intent.uploadMode !== "MULTIPART_PRESIGNED" || intent.partSize === null || intent.partCount === null) {
        throw new Error("对象存储未配置：服务端未提供预签名分片能力。");
      }
      if (intent.parts.length !== intent.partCount || intent.parts[0]?.partNumber !== 1) {
        throw new Error("服务端返回的分片元数据不完整。");
      }
      setTotalParts(intent.partCount);

      const controller = new AbortController();
      abortControllerRef.current = controller;
      const uploaded: UploadedPart[] = [];
      for (const part of intent.parts) {
        if (cancellingRef.current) return;
        const start = (part.partNumber - 1) * intent.partSize;
        const end = Math.min(part.partNumber * intent.partSize, buffer.byteLength);
        const response = await fetch(part.url, {
          method: "PUT",
          credentials: "omit",
          headers: { ...part.headers },
          body: buffer.slice(start, end),
          signal: controller.signal,
        });
        if (cancellingRef.current) return;
        if (!response.ok) {
          throw new Error(`第 ${part.partNumber}/${intent.partCount} 部分上传失败（HTTP ${response.status}）。`);
        }
        const etag = response.headers.get("ETag");
        if (!etag) {
          throw new Error(`第 ${part.partNumber} 部分未返回 ETag。`);
        }
        uploaded.push({ partNumber: part.partNumber, etag });
        setUploadedParts(uploaded.length);
        setProgress(Math.round((uploaded.length / intent.partCount) * 100));
        if (cancellingRef.current) return;
      }
      parts = uploaded;
      if (cancellingRef.current) return;

      setPhase("completing");
      const accepted = await completeUpload(intent.id, parts);
      if (cancellingRef.current) return;
      setJob(accepted);
      intentIdRef.current = null;
      abortControllerRef.current = null;
      setProgress(100);
      setPhase("queued");
      onUploaded(accepted);
    } catch (reason) {
      if (cancellingRef.current) return;
      if (reason instanceof DOMException && reason.name === "AbortError") return;
      const message = reason instanceof Error ? reason.message : "上传失败，请稍后重试。";
      setError(reason instanceof TypeError && /fetch/i.test(message)
        ? "浏览器无法连接对象存储。请确认 MinIO 已启动，并且上传地址可从当前浏览器访问。"
        : message);
      setPhase("error");
      if (intentIdRef.current) {
        await abortCurrent();
      }
    }
  };

  const submitting = phase === "hashing" || phase === "uploading" || phase === "completing";
  const cancelling = phase === "cancelling";

  return (
    <dialog ref={ref} className="scene-dialog upload-dialog" aria-labelledby="upload-dialog-title" onCancel={handleCancel}>
      <form
        className="model-dialog__form"
        onSubmit={(event) => {
          event.preventDefault();
          void startUpload();
        }}
        noValidate
      >
        <header className="model-dialog__head">
          <div>
            <h2 id="upload-dialog-title">{explorationSessionId ? "添加探索素材" : "上传素材"}</h2>
            <p>{explorationSessionId
              ? "文件先进入探索区，确认候选场景后可直接复用。"
              : "文件将绑定当前轮次和子场景，并保留完整来源定位。"}</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={handleCancel} disabled={cancelling}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          {phase !== "queued" ? (
            <div className="upload-scope" role="note">
              <span className="upload-scope__mark"><Glyph name="link" size={16} /></span>
              <div>
                <b>{explorationSessionId ? "探索素材隔离区" : "当前轮次 · 当前子场景"}</b>
                <span>{explorationSessionId ? "接受候选场景后复用原文件与解析结果" : "共享范围：本轮次；文件内容保持不可变"}</span>
              </div>
              <Status tone="info">来源可追溯</Status>
            </div>
          ) : null}

          {phase === "queued" && job ? (
            <div className="dialog-processing" role="status">
              <Glyph name="history" size={16} />
              <div>
                <b>文件已传输，正在进行安全校验</b>
                <span>任务 {job.jobId} · {jobStatus ? JOB_STATUS_LABELS[jobStatus.status] : "等待安全校验"}</span>
              </div>
            </div>
          ) : null}

          {phase !== "queued" ? (
            <div className="field">
              <span>选择文件</span>
              <label className={`file-picker ${file ? "file-picker--selected" : ""}`}>
              <input
                type="file"
                name="file"
                aria-label="文件（PDF / DOCX / XLSX / TXT）"
                accept=".pdf,.docx,.xlsx,.txt"
                disabled={submitting}
                onChange={(event) => selectFile(event.currentTarget.files?.[0] ?? null)}
              />
                <span className="file-picker__icon"><Glyph name={file ? "check" : "file"} size={22} /></span>
                <span className="file-picker__content">
                  <b>{file ? file.name : "点击选择 PDF、DOCX、XLSX 或 TXT"}</b>
                  <small>{file ? `${formatBytes(file.size)} · 已通过本地格式检查` : "单文件最大 200MB；不支持旧版 .doc 和 .xls"}</small>
                </span>
                <span className="file-picker__action">{file ? "重新选择" : "选择文件"}</span>
              </label>
              <small>选择后将在浏览器本地计算 SHA-256，文件内容不会写入浏览器存储。</small>
              {fileError ? <small className="field-error" role="alert">{fileError}</small> : null}
            </div>
          ) : null}

          {phase !== "queued" && !explorationSessionId ? (
            <div className="field">
              <span>用途分区</span>
              <div className="choice-grid">
                {partitionOptions.map((option) => (
                  <label key={option.value} className={`choice-card ${partition === option.value ? "choice-card--selected" : ""}`}>
                    <input type="radio" name="partition" value={option.value} checked={partition === option.value}
                      disabled={submitting}
                      onChange={() => {
                        setPartition(option.value);
                        if (option.value === "LABELED_HOLDOUT") setRegulatorySource(false);
                      }} />
                    <span className="choice-card__control" aria-hidden="true" />
                    <span className="choice-card__copy">
                      <b>{option.title}</b>
                      <small>{option.description}</small>
                      <code>{partitionLabels[option.value]}</code>
                    </span>
                  </label>
                ))}
              </div>
            </div>
          ) : null}

          {phase !== "queued" && !explorationSessionId ? (
            <label className={`toggle-field ${partition === "LABELED_HOLDOUT" ? "toggle-field--disabled" : ""}`}>
              <span>
                <b>标记为监管依据</b>
                <small>{partition === "LABELED_HOLDOUT" ? "留出评测素材不能作为监管对齐依据" : "开启后，可在监管对齐时引用此文件"}</small>
              </span>
              <span className="switch">
                <input type="checkbox" name="regulatorySource" disabled={submitting || partition === "LABELED_HOLDOUT"}
                  checked={regulatorySource}
                  onChange={(event) => setRegulatorySource(event.currentTarget.checked)} />
                <span />
              </span>
            </label>
          ) : null}

          {submitting || cancelling || sha256 ? (
            <div className="upload-progress" aria-live="polite">
              <div className="upload-progress__text">
                <span>
                  {phase === "hashing" ? "正在计算 SHA-256…"
                    : phase === "uploading" ? `正在上传分片 ${Math.min(uploadedParts + 1, totalParts ?? 1)}/${totalParts ?? "…"}`
                    : phase === "completing" ? "正在提交完成…"
                    : phase === "cancelling" ? "正在取消上传…"
                    : "准备上传…"}
                </span>
                {sha256 ? <code>sha256:{sha256.slice(0, 16)}…</code> : null}
              </div>
              <progress className="job-strip__progress" max={100} value={phase === "hashing" || phase === "cancelling" ? 0 : progress}
                aria-label="上传进度" />
              <strong>{phase === "hashing" || phase === "cancelling" ? "—" : `${progress}%`}</strong>
            </div>
          ) : null}

          {phase === "error" ? (
            <div className="upload-error" role="alert">
              <Glyph name="warning" size={17} />
              <div><b>素材没有上传成功</b><span>{error}</span></div>
            </div>
          ) : null}
        </div>
        <footer className="model-dialog__foot">
          {phase === "queued" ? (
            <>
              <Button type="button" className="button--quiet" onClick={onClose}>完成</Button>
              <Button type="button" className="button--primary" onClick={() => void refreshJob()}>刷新状态</Button>
            </>
          ) : phase === "error" ? (
            <>
              <Button type="button" className="button--quiet" onClick={onClose}>关闭</Button>
              <Button type="submit" className="button--primary" disabled={!file}>重新上传</Button>
            </>
          ) : (
            <>
              <Button type="button" className="button--quiet" onClick={handleCancel} disabled={cancelling}>
                {cancelling ? "正在取消…" : submitting ? "取消上传" : "取消"}
              </Button>
              <Button type="submit" className="button--primary" disabled={!file || submitting || cancelling}>
                {submitting ? "上传中…" : "开始上传"}
              </Button>
            </>
          )}
        </footer>
      </form>
    </dialog>
  );
}
