import type { OperationReadiness } from "../lib/api";
import { Button, Glyph } from "./Ui";

export function OperationReadinessNotice({ report, label, onNavigate }: {
  report: OperationReadiness | null;
  label: string;
  onNavigate: (href: string) => void;
}) {
  if (!report) return null;
  if (report.ready) {
    return <div className="operation-readiness operation-readiness--ready" role="status">
      <Glyph name="check" size={14}/><span><b>{label}已就绪</b> · 业务条件与所需 Agent 均已通过预检</span>
    </div>;
  }
  const agentRepair = report.blockers.some((item) => item.actionHref === "/agents");
  return <div className="operation-readiness operation-readiness--blocked" role="alert">
    <Glyph name="warning" size={15}/>
    <div><b>{label}尚未就绪</b><ul>{report.blockers.map((item) =>
      <li key={`${item.code}-${item.message}`}><code>{item.code}</code><span>{item.message}</span></li>)}</ul></div>
    {agentRepair ? <Button className="button--quiet button--small" onClick={() => onNavigate("/agents")}>
      前往智能体准备
    </Button> : null}
  </div>;
}
