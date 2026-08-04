import { FormEvent, useEffect, useRef, useState } from "react";
import { Button, Glyph, Status } from "../components/Ui";
import { abortUpload, completeUpload, createUploadIntent, getJob } from "../lib/api";
import type { Job, MaterialJobAccepted, MaterialPartition, UploadedPart } from "../lib/api";
import { MATERIAL_STATUS_LABELS, mediaTypeForFile, partitionLabels, toStatusTone, validateMaterialFile } from "../domain";
import { sha256Hex } from "../lib/hashes";

type Phase = "form" | "hashing" | "uploading" | "completing" | "queued" | "cancelling" | "error";

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
      setError(reason instanceof Error ? reason.message : "上传失败，请稍后重试。");
      setPhase("error");
      if (intentIdRef.current) {
        await abortCurrent();
      }
    }
  };

  const submitting = phase === "hashing" || phase === "uploading" || phase === "completing";
  const cancelling = phase === "cancelling";

  return (
    <dialog ref={ref} className="scene-dialog" aria-labelledby="upload-dialog-title" onCancel={handleCancel}>
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
              ? "素材先进入隔离 staging；接受候选后复用同一文件与解析结果。"
              : "固定绑定当前轮次与当前子场景（ROUND 范围）。"}</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭" onClick={handleCancel} disabled={cancelling}>
            <Glyph name="close" size={16} />
          </button>
        </header>
        <div className="model-dialog__body">
          {phase === "queued" && job ? (
            <div className="dialog-success" role="status">
              <Glyph name="check" size={16} />
              <div>
                <b>上传完成，校验任务已排队</b>
                <span>任务 {job.jobId} · 当前状态 {jobStatus ? MATERIAL_STATUS_LABELS[jobStatus.status] ?? jobStatus.status : "QUEUED"}</span>
              </div>
            </div>
          ) : null}

          {phase !== "queued" ? (
            <label className="field">
              <span>文件（PDF / DOCX / XLSX / TXT）</span>
              <input
                type="file"
                name="file"
                accept=".pdf,.docx,.xlsx,.txt"
                disabled={submitting}
                onChange={(event) => selectFile(event.currentTarget.files?.[0] ?? null)}
              />
              <small>200MB 上限；.doc 与 .xls 明确不支持。文件在本浏览器本地计算 SHA-256。</small>
              {fileError ? <small className="field-error" role="alert">{fileError}</small> : null}
              {file ? <small className="field-hint">已选择：{file.name}（{formatBytes(file.size)}）</small> : null}
            </label>
          ) : null}

          {phase !== "queued" && !explorationSessionId ? (
            <div className="field">
              <span>用途分区</span>
              <div className="role-checks">
                {(["SOURCE", "LABELED_TRAIN", "LABELED_HOLDOUT"] as MaterialPartition[]).map((value) => (
                  <label key={value} className="role-check">
                    <input type="radio" name="partition" value={value} checked={partition === value}
                      disabled={submitting}
                      onChange={() => {
                        setPartition(value);
                        if (value === "LABELED_HOLDOUT") setRegulatorySource(false);
                      }} />
                    <span>{value}</span>
                    <small>{partitionLabels[value]}</small>
                  </label>
                ))}
              </div>
            </div>
          ) : null}

          {phase !== "queued" && !explorationSessionId ? (
            <label className="field field--row">
              <span>监管依据</span>
              <input type="checkbox" name="regulatorySource" disabled={submitting || partition === "LABELED_HOLDOUT"}
                checked={regulatorySource}
                onChange={(event) => setRegulatorySource(event.currentTarget.checked)} />
            </label>
          ) : null}

          {!explorationSessionId && partition === "LABELED_HOLDOUT" ? (
            <small className="field-hint">留出评测分区不能作为监管对齐依据。</small>
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

          {phase === "error" ? <div className="form-error" role="alert">{error}</div> : null}
        </div>
        <footer className="model-dialog__foot">
          {phase === "queued" ? (
            <>
              <Button type="button" className="button--quiet" onClick={onClose}>完成</Button>
              <Button type="button" className="button--primary" onClick={() => void refreshJob()}>刷新状态</Button>
            </>
          ) : phase === "error" ? (
            <Button type="button" className="button--quiet" onClick={onClose}>关闭</Button>
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
