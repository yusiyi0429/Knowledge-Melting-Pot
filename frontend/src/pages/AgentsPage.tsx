import { useState } from "react";
import { Button, DemoNotice, Glyph, PageHeader, Status } from "../components/Ui";
import { agents } from "../fixtures";

export function AgentsPage() {
  const [enabled, setEnabled] = useState<Record<string, boolean>>(() => Object.fromEntries(agents.map((agent) => [agent.id, true])));
  const [scope, setScope] = useState("偿债能力与担保因素综合研判");

  return (
    <div className="page">
      <DemoNotice />
      <PageHeader eyebrow="治理 / 智能体挂载" title="七种角色，一条显式工作流" description="每个角色固定 Skill、模型配置版本和作用域；SubScene 覆盖优先于 Scene 默认。" actions={<Button className="button--primary">保存为配置新版本</Button>} />
      <section className="scope-bar">
        <div><span className="scope-bar__mark"><Glyph name="scene" size={17}/></span><div><b>配置作用域</b><small>Scene 默认 → SubScene 覆盖</small></div></div>
        <label><span>主场景</span><select><option>对公贷款五级分类</option></select></label>
        <Glyph name="chevron" size={14}/>
        <label><span>子场景</span><select value={scope} onChange={(event) => setScope(event.target.value)}>{["逾期天数与分类下迁", "偿债能力与担保因素综合研判", "重组资产分类"].map((item) => <option key={item}>{item}</option>)}</select></label>
        <Status tone="info">覆盖 4 项 Scene 默认</Status>
      </section>

      <div className="stage-groups">
        {["环节一", "环节二", "环节三"].map((stage) => (
          <section key={stage} className="stage-group">
            <header><span>{stage}</span><div className="stage-group__line"/><small>{stage === "环节一" ? "定义场景与素材" : stage === "环节二" ? "萃取、冲突检测与对齐" : "从定稿 Revision 生成交付资产"}</small></header>
            <div className="agent-grid">
              {agents.filter((agent) => agent.stage === stage).map((agent) => (
                <article className={`agent-card ${enabled[agent.id] ? "" : "agent-card--disabled"}`} key={agent.id}>
                  <div className="agent-card__head"><span className="agent-index">{agents.indexOf(agent) + 1}</span><div><h3>{agent.name}</h3><span>触发：{agent.trigger}</span></div><label className="switch"><input type="checkbox" checked={enabled[agent.id]} onChange={(event) => setEnabled((value) => ({ ...value, [agent.id]: event.target.checked }))}/><span/></label></div>
                  <p>{agent.description}</p>
                  <div className="agent-config"><label><span>挂载 Skill</span><select defaultValue={agent.skill}><option>{agent.skill}</option><option>继承 Scene 默认</option></select></label><div><label><span>模型</span><select defaultValue={agent.model}><option>{agent.model}</option><option>DeepSeek-V3</option><option>Qwen2.5-72B</option></select></label><label><span>{agent.optionLabel}</span><select defaultValue={agent.option}><option>{agent.option}</option><option>继承默认</option></select></label></div></div>
                  <footer><span><Glyph name="lock" size={13}/>{agent.version}</span><button>查看版本关系</button></footer>
                </article>
              ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
