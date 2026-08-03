import { useEffect, useRef, useState } from "react";
import { Button, DemoNotice, Glyph, PageHeader, Status } from "../components/Ui";
import { models as modelFixtures } from "../fixtures";
import { toStatusTone } from "../domain";

export function ModelsPage() {
  const [testing, setTesting] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<string | null>(null);
  const testTimer = useRef<number | null>(null);

  useEffect(() => () => {
    if (testTimer.current !== null) window.clearTimeout(testTimer.current);
  }, []);

  const testConnection = (id: string) => {
    setTesting(id); setLastResult(null);
    if (testTimer.current !== null) window.clearTimeout(testTimer.current);
    testTimer.current = window.setTimeout(() => {
      setTesting(null);
      setLastResult(id);
      testTimer.current = null;
    }, 800);
  };
  return (
    <div className="page">
      <DemoNotice />
      <PageHeader eyebrow="平台 / 模型接入" title="模型连接与生成参数分开版本化" description="密钥只写不读；前端只获得 credentialConfigured，不回显任何 Secret。" actions={<Button className="button--primary"><Glyph name="plus"/>新增模型连接</Button>} />
      <section className="model-table" aria-label="模型连接列表">
        <div className="model-table__head"><span>连接</span><span>Provider / Model ID</span><span>允许的 Base URL</span><span>凭证</span><span>状态</span><span>操作</span></div>
        {modelFixtures.map((model) => (
          <article key={model.id}>
            <div className="model-name"><span><Glyph name="model" size={18}/></span><div><b>{model.name}</b><small>{model.id}</small></div></div>
            <div><b>{model.provider}</b><code>{model.modelId}</code></div>
            <code className="base-url">{model.baseUrl}</code>
            <div><Status tone={model.credentialConfigured ? "success" : "warning"}>{model.credentialConfigured ? "已配置" : "未配置"}</Status><small>密钥不可读取</small></div>
            <Status tone={lastResult === model.id ? "success" : toStatusTone(model.state)}>{lastResult === model.id ? "演示测试通过" : model.state === "CONNECTED" ? "已连接" : "未测试"}</Status>
            <div className="row-actions"><button onClick={() => testConnection(model.id)} disabled={testing === model.id}>{testing === model.id ? "测试中…" : "测试连接"}</button><button>编辑</button></div>
          </article>
        ))}
      </section>
      <div className="model-footnotes"><div><b>SSRF 防护</b><p>Base URL 必须命中管理员白名单，并在 DNS 解析与重定向后再次校验。</p></div><div><b>配置快照</b><p>发布 Manifest 记录 Provider、Model ID 和生成参数哈希，不记录 API Key。</p></div><div><b>Claude 接入</b><p>仅允许通过企业兼容网关或 OpenRouter，不提供 Anthropic 直连。</p></div></div>
    </div>
  );
}
