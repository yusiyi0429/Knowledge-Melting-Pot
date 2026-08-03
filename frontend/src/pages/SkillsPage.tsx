import { useState } from "react";
import { Button, DemoNotice, Glyph, PageHeader, Status } from "../components/Ui";
import { skills } from "../fixtures";

export function SkillsPage() {
  const [filter, setFilter] = useState<"ALL" | "TEMPLATE" | "INSTANCE">("ALL");
  const visible = skills.filter((skill) => filter === "ALL" || skill.kind === filter);
  return (
    <div className="page">
      <DemoNotice />
      <PageHeader eyebrow="治理 / Skill 库" title="模板是起点，版本才是交付物" description="Skill 首发只作为提示和资源包使用；任何上传脚本都不会在工作台内执行。" actions={<Button className="button--primary"><Glyph name="plus"/>新建场景实例</Button>} />
      <div className="security-note"><Glyph name="lock" size={17}/><div><b>安全边界</b><p>当前里程碑不执行 Skill 包内的 Shell、Python 或校验脚本。挂载时固定 SkillVersion 与 packageHash。</p></div><Status tone="success">只读资源模式</Status></div>
      <div className="section-head section-head--compact"><div className="segmented">{(["ALL", "TEMPLATE", "INSTANCE"] as const).map((item) => <button key={item} className={filter === item ? "active" : ""} onClick={() => setFilter(item)}>{{ ALL: "全部", TEMPLATE: "通用模板", INSTANCE: "场景实例" }[item]}</button>)}</div><label className="inline-search"><Glyph name="search" size={15}/><input placeholder="搜索 Skill 名称或场景"/></label></div>
      <div className="skill-grid">
        {visible.map((skill) => (
          <article className="skill-card" key={skill.id}>
            <header><span className="skill-glyph"><Glyph name="skill" size={19}/></span><Status tone={skill.kind === "TEMPLATE" ? "purple" : "info"}>{skill.kind === "TEMPLATE" ? "通用 · 只读" : "场景实例"}</Status></header>
            <h3>{skill.name} <code>{skill.version}</code></h3><p>{skill.description}</p>
            <dl>{skill.parent ? <div><dt>Fork 自</dt><dd>{skill.parent}</dd></div> : null}{skill.scene ? <div><dt>适用场景</dt><dd>{skill.scene}</dd></div> : null}<div><dt>包哈希</dt><dd><code>{skill.packageHash}</code></dd></div></dl>
            <footer><button>查看 Manifest</button><button className="skill-card__primary">{skill.kind === "TEMPLATE" ? "复制为实例" : "创建新版本"}</button></footer>
          </article>
        ))}
      </div>
    </div>
  );
}
