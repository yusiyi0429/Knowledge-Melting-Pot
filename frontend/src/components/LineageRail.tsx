import { Glyph } from "./Ui";

export type LineageStage = "materials" | "extraction" | "assets" | "release";

const stages: Array<{ id: LineageStage; label: string; detail: string }> = [
  { id: "materials", label: "素材", detail: "5 份已固定" },
  { id: "extraction", label: "萃取", detail: "Revision rev-07" },
  { id: "assets", label: "资产", detail: "4 / 5 就绪" },
  { id: "release", label: "发布", detail: "部分覆盖" },
];

export function LineageRail({ active = "extraction", compact = false }: { active?: LineageStage; compact?: boolean }) {
  const activeIndex = stages.findIndex((stage) => stage.id === active);
  return (
    <section className={`lineage ${compact ? "lineage--compact" : ""}`} aria-label="知识溯源链路">
      <div className="lineage__label"><Glyph name="link" size={15}/><span>LINEAGE</span></div>
      <ol>
        {stages.map((stage, index) => {
          const state = index < activeIndex ? "done" : index === activeIndex ? "active" : "next";
          return (
            <li key={stage.id} className={`lineage__stage lineage__stage--${state}`}>
              <span className="lineage__node">{state === "done" ? <Glyph name="check" size={12}/> : index + 1}</span>
              <span className="lineage__text"><b>{stage.label}</b><small>{stage.detail}</small></span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
