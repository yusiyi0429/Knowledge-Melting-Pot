import { useMemo, useState } from "react";
import { LineageRail } from "../components/LineageRail";
import { Button, DemoNotice, Glyph, PageHeader, Status } from "../components/Ui";
import { scenes } from "../fixtures";
import { toStatusTone } from "../domain";

export function DashboardPage({ onNavigate }: { onNavigate: (href: string) => void }) {
  const [filter, setFilter] = useState<"all" | "active" | "published">("all");
  const visibleScenes = useMemo(() => scenes.filter((scene) => {
    if (filter === "active") return scene.status === "ALIGNING" || scene.status === "EXTRACTING";
    if (filter === "published") return scene.status === "PUBLISHED" || scene.status === "PARTIALLY_PUBLISHED";
    return true;
  }), [filter]);

  return (
    <div className="page dashboard-page">
      <DemoNotice />
      <PageHeader
        eyebrow="工作台 / 场景库"
        title="知识正在形成可追溯的资产"
        description="从原始素材到发布快照，每一次萃取、人工确认和生成结果都保留版本关系。"
        actions={<Button className="button--primary" onClick={() => onNavigate("/scenes/corporate-loan-classification")}><Glyph name="plus"/>新建萃取场景</Button>}
      />

      <section className="dashboard-overview" aria-label="工作台概览">
        <LineageRail active="release" />
        <dl className="signal-grid">
          <div><dt>场景</dt><dd>3</dd><small>1 个正在萃取</small></div>
          <div><dt>当前 Revision</dt><dd>rev-07</dd><small>2 条待人工确认</small></div>
          <div><dt>就绪资产</dt><dd>35</dd><small>按来源 Revision 固定</small></div>
          <div><dt>发布覆盖</dt><dd>8 / 12</dd><small>4 个子场景未覆盖</small></div>
        </dl>
      </section>

      <section className="section-block">
        <div className="section-head">
          <div><h2>场景清单</h2><p>按业务场景组织素材、Revision、资产和发布快照。</p></div>
          <div className="segmented" aria-label="筛选场景">
            {(["all", "active", "published"] as const).map((value) => (
              <button key={value} className={filter === value ? "active" : ""} aria-pressed={filter === value} onClick={() => setFilter(value)}>
                {{ all: "全部", active: "进行中", published: "已发布" }[value]}
              </button>
            ))}
          </div>
        </div>
        <div className="scene-grid">
          {visibleScenes.map((scene) => (
            <article className="scene-card" key={scene.id}>
              <button className="scene-card__target" aria-label={`打开${scene.name}`} onClick={() => onNavigate(`/scenes/${scene.id}`)} />
              <div className="scene-card__top">
                <Status tone={toStatusTone(scene.status)}>{scene.statusLabel}</Status>
                <span className="round-chip">{scene.round}</span>
              </div>
              <h3>{scene.name}</h3>
              <p>{scene.description}</p>
              <div className="scene-card__lineage">
                <span><b>{scene.materialCount}</b> 素材</span><Glyph name="chevron" size={13}/>
                <span><b>{scene.subsceneCount}</b> 子场景</span><Glyph name="chevron" size={13}/>
                <span><b>{scene.assetCount}</b> 资产</span>
              </div>
              <footer><span className="mini-avatar">{scene.owner.slice(0, 1)}</span><b>{scene.owner}</b><span>{scene.updatedAt}</span></footer>
            </article>
          ))}
          <button className="new-scene-card" onClick={() => onNavigate("/scenes/corporate-loan-classification")}>
            <span><Glyph name="plus" size={22}/></span><b>新建萃取场景</b><small>从目标和素材开始</small>
          </button>
        </div>
      </section>
    </div>
  );
}
