import { useMemo, useState } from "react";
import { LineageRail, type LineageStage } from "../components/LineageRail";
import { Button, DemoNotice, Glyph, Status } from "../components/Ui";
import { assets as assetFixtures, initialMarkdown, materials as materialFixtures, scenes, sourceRefs, subscenes as subsceneFixtures } from "../fixtures";
import { partitionLabels, releaseCanInclude, toStatusTone, type Material, type Subscene } from "../domain";
import { useJobEvents } from "../hooks/useJobEvents";

const steps = [
  { id: 1, title: "场景与素材", detail: "目标 · 素材 · 子场景", stage: "materials" },
  { id: 2, title: "知识萃取与对齐", detail: "萃取 · Revision · 来源", stage: "extraction" },
  { id: 3, title: "知识生成及发布", detail: "五类资产 · 发布快照", stage: "assets" },
] as const;

export function ScenePage({ sceneId, onNavigate }: { sceneId: string; onNavigate: (href: string) => void }) {
  const scene = scenes.find((item) => item.id === sceneId) ?? scenes[0];
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [subscenes, setSubscenes] = useState<Subscene[]>(subsceneFixtures);
  const [selectedSubscene, setSelectedSubscene] = useState(subsceneFixtures[1].id);
  const [materials, setMaterials] = useState<Material[]>(materialFixtures);
  const [markdown, setMarkdown] = useState(initialMarkdown);
  const [savedMarkdown, setSavedMarkdown] = useState(initialMarkdown);
  const [jobId, setJobId] = useState<string | null>(null);
  const [toast, setToast] = useState("");
  const [releaseSelection, setReleaseSelection] = useState<string[]>([subsceneFixtures[2].id]);
  const { events, connection } = useJobEvents({ jobId, demo: true });

  const activeSubscene = subscenes.find((item) => item.id === selectedSubscene) ?? subscenes[0];
  const isDirty = markdown !== savedMarkdown;
  const jobProgress = events.at(-1)?.percent ?? 0;
  const currentLineage = steps.find((item) => item.id === step)?.stage as LineageStage;

  const addSubscene = () => {
    const id = `subscene-${subscenes.length + 1}`;
    setSubscenes((items) => [...items, { id, name: "待命名子场景", description: "说明该子场景的业务边界和期望产物。", revision: "尚未萃取", releaseState: "BLOCKED" }]);
    setSelectedSubscene(id);
  };

  const updatePartition = (id: string, partition: Material["partition"]) => {
    setMaterials((items) => items.map((item) => item.id === id ? { ...item, partition } : item));
  };

  const startDemoJob = () => {
    setJobId(`demo-job-${Date.now()}`);
    setToast("已创建离线演示 Job；下面模拟 SSE 事件流。 ");
  };

  const selectedCoverage = useMemo(() => subscenes.filter((item) => releaseSelection.includes(item.id)), [releaseSelection, subscenes]);
  const publishBlocked = selectedCoverage.length === 0 || selectedCoverage.some((item) => !releaseCanInclude(item, item.id === "solvency" ? assetFixtures : assetFixtures.map((asset) => ({ ...asset, state: "READY" }))));

  return (
    <div className="scene-workspace">
      <div className="scene-context">
        <button className="back-link" onClick={() => onNavigate("/")}><span aria-hidden="true">←</span> 场景库</button>
        <div className="scene-context__title">
          <div><span className="eyebrow">SCENE / {scene.id}</span><h1>{scene.name}</h1></div>
          <Status tone={toStatusTone(scene.status)}>{scene.statusLabel} · {scene.round}</Status>
        </div>
        <div className="rounds" aria-label="萃取轮次">
          <span><Glyph name="history" size={15}/> 萃取轮次</span>
          <button>v1.0 · 已发布</button><button className="active">v1.1 · 当前</button><button><Glyph name="plus" size={13}/> 新一轮</button>
        </div>
      </div>

      <div className="scene-grid-layout">
        <aside className="workflow-nav">
          <DemoNotice compact />
          <ol>
            {steps.map((item) => (
              <li key={item.id} className={step === item.id ? "active" : step > item.id ? "done" : ""}>
                <button onClick={() => setStep(item.id)}>
                  <span className="workflow-nav__node">{step > item.id ? <Glyph name="check" size={14}/> : item.id}</span>
                  <span><b>{item.title}</b><small>{item.detail}</small></span>
                </button>
              </li>
            ))}
          </ol>
          <div className="workflow-nav__meta"><span>当前子场景</span><strong>{activeSubscene?.name}</strong><small>{activeSubscene?.revision}</small></div>
        </aside>

        <div className="workflow-main">
          <LineageRail active={currentLineage} compact />
          {toast ? <div className="toast-inline" role="status"><Glyph name="check" size={15}/>{toast}<button onClick={() => setToast("")} aria-label="关闭提示"><Glyph name="close" size={14}/></button></div> : null}

          {step === 1 ? (
            <StepMaterials
              scene={scene}
              subscenes={subscenes}
              selected={selectedSubscene}
              materials={materials}
              onSelect={setSelectedSubscene}
              onAdd={addSubscene}
              onPartition={updatePartition}
              onNext={() => setStep(2)}
            />
          ) : null}

          {step === 2 ? (
            <StepExtraction
              subscene={activeSubscene}
              markdown={markdown}
              isDirty={isDirty}
              onMarkdown={setMarkdown}
              onSave={() => { setSavedMarkdown(markdown); setToast("已在离线状态中模拟保存 Revision；没有写入后端。"); }}
              onRun={startDemoJob}
              events={events}
              connection={connection}
              progress={jobProgress}
              onNext={() => setStep(3)}
            />
          ) : null}

          {step === 3 ? (
            <StepAssets
              subscenes={subscenes}
              releaseSelection={releaseSelection}
              onSelection={setReleaseSelection}
              publishBlocked={publishBlocked}
              onPublish={() => setToast("离线演示不会创建真实 Release；正式 API 将冻结 Manifest。")}
            />
          ) : null}
        </div>
      </div>
    </div>
  );
}

function StepMaterials({ scene, subscenes, selected, materials, onSelect, onAdd, onPartition, onNext }: {
  scene: (typeof scenes)[number];
  subscenes: Subscene[];
  selected: string;
  materials: Material[];
  onSelect: (id: string) => void;
  onAdd: () => void;
  onPartition: (id: string, partition: Material["partition"]) => void;
  onNext: () => void;
}) {
  return (
    <section className="workflow-step" aria-labelledby="step-materials-title">
      <header className="step-heading"><div><span>STEP 01</span><h2 id="step-materials-title">定义边界，固定本轮素材</h2><p>子场景决定萃取范围；素材分区决定数据能进入哪条处理链路。</p></div><Button className="button--primary" onClick={onNext}>进入知识萃取 <Glyph name="chevron" size={15}/></Button></header>
      <div className="materials-layout">
        <section className="panel">
          <div className="panel__head"><div><span className="panel__index">A</span><h3>场景与子场景</h3></div><Button className="button--quiet button--small" onClick={onAdd}><Glyph name="plus" size={14}/> 添加子场景</Button></div>
          <div className="field-grid">
            <label className="field"><span>主场景名称</span><input defaultValue={scene.name}/></label>
            <label className="field field--full"><span>本轮萃取说明</span><textarea defaultValue={scene.description}/></label>
          </div>
          <div className="subscene-stack">
            {subscenes.map((subscene, index) => (
              <button key={subscene.id} className={`subscene-item ${selected === subscene.id ? "active" : ""}`} onClick={() => onSelect(subscene.id)}>
                <span className="subscene-item__index">{String(index + 1).padStart(2, "0")}</span>
                <span><b>{subscene.name}</b><small>{subscene.description}</small></span>
                <Status tone={toStatusTone(subscene.releaseState)}>{subscene.revision}</Status>
              </button>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel__head"><div><span className="panel__index">B</span><h3>本轮素材</h3></div><span className="panel__count">{materials.length} 份 · 200MB / 文件</span></div>
          <button className="upload-zone"><Glyph name="plus" size={18}/><span><b>添加 PDF / DOCX / XLSX / TXT</b><small>正式环境通过预签名分片上传；DOC / XLS 明确不支持</small></span></button>
          <div className="material-list">
            {materials.map((material) => (
              <article className="material-row" key={material.id}>
                <span className={`file-type file-type--${material.kind.toLowerCase()}`}>{material.kind}</span>
                <div className="material-row__main"><b>{material.name}</b><small>{material.size} · {material.locator} · {material.tag}</small></div>
                <label className="partition-select"><span className="sr-only">{material.name}的数据分区</span><select value={material.partition} onChange={(event) => onPartition(material.id, event.target.value as Material["partition"])}>
                  {Object.entries(partitionLabels).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
                </select></label>
                <Status tone="success">已解析</Status>
              </article>
            ))}
          </div>
          <div className="partition-legend"><b>数据隔离</b><span>事实素材与训练标注可参与萃取</span><span className="holdout">留出评测永不进入 Prompt、检索或 QA 生成</span></div>
        </section>
      </div>
    </section>
  );
}

function StepExtraction({ subscene, markdown, isDirty, onMarkdown, onSave, onRun, events, connection, progress, onNext }: {
  subscene: Subscene;
  markdown: string;
  isDirty: boolean;
  onMarkdown: (value: string) => void;
  onSave: () => void;
  onRun: () => void;
  events: ReturnType<typeof useJobEvents>["events"];
  connection: ReturnType<typeof useJobEvents>["connection"];
  progress: number;
  onNext: () => void;
}) {
  return (
    <section className="workflow-step" aria-labelledby="step-extraction-title">
      <header className="step-heading"><div><span>STEP 02</span><h2 id="step-extraction-title">萃取、核对并固定 Revision</h2><p>{subscene.name} · 所有结论都必须保留可定位来源。</p></div><div className="step-heading__actions"><Button className="button--quiet" onClick={onRun}><Glyph name="play" size={14}/>演示萃取 Job</Button><Button className="button--primary" onClick={onNext}>查看生成资产 <Glyph name="chevron" size={15}/></Button></div></header>

      <div className="job-strip" aria-live="polite">
        <div className={`job-strip__pulse job-strip__pulse--${connection}`} />
        <div><b>{connection === "idle" ? "尚未启动任务" : connection === "closed" ? "演示 Job 已完成" : "SSE 演示事件流"}</b><small>{connection === "idle" ? "点击“演示萃取 Job”查看异步状态" : events.at(-1)?.message ?? "正在建立连接…"}</small></div>
        <progress className="job-strip__progress" max={100} value={progress} aria-label="任务进度"/><strong>{progress}%</strong>
      </div>

      <div className="editor-layout">
        <section className="panel document-panel">
          <div className="document-toolbar">
            <div><Status tone={isDirty ? "warning" : "success"}>{isDirty ? "未保存" : "已保存"}</Status><code>{subscene.revision}</code><span>ETag: W/“rev-07”</span></div>
            <div><Button className="button--text"><Glyph name="history" size={14}/>历史 Revision</Button><Button className="button--primary button--small" disabled={!isDirty} onClick={onSave}>保存新 Revision</Button></div>
          </div>
          <label className="markdown-editor"><span className="sr-only">知识文档 Markdown</span><textarea spellCheck={false} value={markdown} onChange={(event) => onMarkdown(event.target.value)}/></label>
          <div className="editor-status"><span>Markdown · UTF-8</span><span>{markdown.split("\n").length} 行</span><span>{sourceRefs.length} 个来源锚点</span></div>
        </section>

        <aside className="source-panel">
          <div className="source-panel__head"><span><Glyph name="link" size={15}/>来源定位</span><b>{sourceRefs.length}</b></div>
          {sourceRefs.map((source) => (
            <article key={source.id} className="source-card">
              <code>[{source.id}]</code><h3>{source.source}</h3><span>{source.locator}</span><blockquote>{source.excerpt}</blockquote><button>在原文中定位 <span aria-hidden="true">↗</span></button>
            </article>
          ))}
          <div className="proposal-card"><span>AI 对齐 Proposal</span><b>2 条建议等待确认</b><p>建议只会生成结构化 Diff；旧 baseRevision 不会覆盖新内容。</p><button>查看 Proposal-014</button></div>
        </aside>
      </div>

      {events.length > 0 ? <section className="event-console"><div className="event-console__head"><span>SSE EVENT REPLAY</span><code>demo-job / Last-Event-ID</code></div>{events.map((event) => <div key={event.eventId}><time>{event.createdAt}</time><code>{event.type}</code><span>{event.stage}</span><b>{event.percent}%</b><p>{event.message}</p></div>)}</section> : null}
    </section>
  );
}

function StepAssets({ subscenes, releaseSelection, onSelection, publishBlocked, onPublish }: {
  subscenes: Subscene[];
  releaseSelection: string[];
  onSelection: (ids: string[]) => void;
  publishBlocked: boolean;
  onPublish: () => void;
}) {
  const toggle = (id: string) => onSelection(releaseSelection.includes(id) ? releaseSelection.filter((item) => item !== id) : [...releaseSelection, id]);
  return (
    <section className="workflow-step" aria-labelledby="step-assets-title">
      <header className="step-heading"><div><span>STEP 03</span><h2 id="step-assets-title">生成资产，检查发布覆盖</h2><p>每类资产独立重试并固定来源 Revision；发布后形成不可变 Manifest。</p></div><Button className="button--quiet"><Glyph name="download" size={15}/>下载就绪资产</Button></header>
      <div className="asset-grid">
        {assetFixtures.map((asset, index) => (
          <article key={asset.id} className={`asset-card asset-card--${asset.state.toLowerCase()}`}>
            <div className="asset-card__number">A{String(index + 1).padStart(2, "0")}</div>
            <div className="asset-card__head"><span className="asset-symbol">{["#", "↳", "{}", "Q", "✓"][index]}</span><Status tone={toStatusTone(asset.state)}>{{ READY: "已就绪", BLOCKED: "阻断", GENERATING: "生成中", FAILED: "失败" }[asset.state]}</Status></div>
            <h3>{asset.name}</h3><code>{asset.format}</code><p>{asset.description}</p>
            {asset.detail ? <div className="asset-card__warning"><Glyph name="warning" size={14}/>{asset.detail}</div> : null}
            <footer><span>{asset.sourceRevision}</span><button disabled={asset.state === "BLOCKED"}>{asset.state === "READY" ? "查看资产" : "补充留出数据"}</button></footer>
          </article>
        ))}
      </div>

      <section className="release-panel">
        <div className="release-panel__head"><div><span className="release-mark"><Glyph name="lock" size={18}/></span><div><h3>发布覆盖矩阵</h3><p>本次选择与历史版本合并为 Scene 级累计快照。</p></div></div><code>next: v1.1</code></div>
        <div className="coverage-table" role="table" aria-label="子场景发布覆盖">
          <div className="coverage-table__head" role="row"><span role="columnheader">本次</span><span role="columnheader">子场景</span><span role="columnheader">文档 Revision</span><span role="columnheader">五类资产</span><span role="columnheader">发布后来源</span></div>
          {subscenes.map((subscene) => {
            const blocked = subscene.releaseState === "BLOCKED";
            const selected = releaseSelection.includes(subscene.id);
            return <div className="coverage-table__row" role="row" key={subscene.id}>
              <span role="cell"><input type="checkbox" checked={selected} onChange={() => toggle(subscene.id)} aria-label={`将${subscene.name}加入本次发布`}/></span>
              <strong role="cell">{subscene.name}</strong><code role="cell">{subscene.revision}</code>
              <span role="cell"><Status tone={blocked ? "warning" : "success"}>{blocked ? "4 / 5 · 阻断" : "5 / 5 · 就绪"}</Status></span>
              <span role="cell">{selected ? "本次 v1.1" : subscene.releaseState === "PUBLISHED" ? "沿用 v1.0" : "尚未覆盖"}</span>
            </div>;
          })}
        </div>
        <div className="release-panel__footer">
          <div className={publishBlocked ? "release-check release-check--blocked" : "release-check"}><Glyph name={publishBlocked ? "warning" : "check"} size={16}/><span>{publishBlocked ? "发布预检未通过：移除阻断项或补齐评测资产。" : "发布预检通过，将冻结内容和配置哈希。"}</span></div>
          <div><Button className="button--quiet">预览 Manifest</Button><Button className="button--primary" disabled={publishBlocked} onClick={onPublish}>发布选中范围</Button></div>
        </div>
      </section>
    </section>
  );
}
