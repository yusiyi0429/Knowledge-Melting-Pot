import type {
  AgentConfig,
  Asset,
  Material,
  SceneSummary,
  SkillSummary,
  SourceRef,
  Subscene,
} from "./domain";

export const scenes: SceneSummary[] = [
  {
    id: "corporate-loan-classification",
    name: "对公贷款五级分类",
    description: "从监管制度、行内细则和标注案例中，萃取可追溯的风险分类规则与研判流程。",
    status: "PARTIALLY_PUBLISHED",
    statusLabel: "部分已发布",
    round: "v1.1",
    subsceneCount: 3,
    materialCount: 8,
    assetCount: 10,
    updatedAt: "12 分钟前",
    owner: "曹征",
  },
  {
    id: "small-business-credit",
    name: "小微企业授信准入",
    description: "从准入政策、行业清单与历史审批案例中萃取红线规则和人工复核边界。",
    status: "EXTRACTING",
    statusLabel: "萃取中",
    round: "v0.3",
    subsceneCount: 4,
    materialCount: 12,
    assetCount: 0,
    updatedAt: "2 小时前",
    owner: "李楠",
  },
  {
    id: "suspicious-transaction",
    name: "可疑交易模式识别",
    description: "沉淀可疑交易识别规则与调查研判流程，形成可加载的场景 Skill。",
    status: "PUBLISHED",
    statusLabel: "已发布",
    round: "v2.0",
    subsceneCount: 5,
    materialCount: 21,
    assetCount: 25,
    updatedAt: "昨天",
    owner: "张三",
  },
];

export const subscenes: Subscene[] = [
  {
    id: "overdue",
    name: "逾期天数与分类下迁",
    description: "按逾期天数、重组状态和例外条件判断最低分类级别。",
    revision: "rev-18",
    releaseState: "PUBLISHED",
  },
  {
    id: "solvency",
    name: "偿债能力与担保因素综合研判",
    description: "综合现金流恶化、担保充足性和抵押物价值的专家研判逻辑。",
    revision: "rev-07",
    releaseState: "BLOCKED",
  },
  {
    id: "restructure",
    name: "重组资产分类",
    description: "识别实质性重组、观察期和再次违约的分类要求。",
    revision: "rev-04",
    releaseState: "READY",
  },
];

export const materials: Material[] = [
  {
    id: "mat-01",
    name: "商业银行金融资产风险分类办法.pdf",
    size: "2.4 MB",
    kind: "PDF",
    tag: "监管依据",
    partition: "SOURCE",
    locator: "48 页",
    status: "READY",
  },
  {
    id: "mat-02",
    name: "行内风险分类实施细则.docx",
    size: "640 KB",
    kind: "DOCX",
    tag: "行内制度",
    partition: "SOURCE",
    locator: "126 个段落",
    status: "READY",
  },
  {
    id: "mat-03",
    name: "已复核分类案例_训练集.xlsx",
    size: "8.2 MB",
    kind: "XLSX",
    tag: "标注案例",
    partition: "LABELED_TRAIN",
    locator: "2,184 行",
    status: "READY",
  },
  {
    id: "mat-04",
    name: "边界案例_留出集.xlsx",
    size: "1.3 MB",
    kind: "XLSX",
    tag: "留出数据",
    partition: "LABELED_HOLDOUT",
    locator: "320 行",
    status: "READY",
  },
  {
    id: "mat-05",
    name: "风险经理访谈记录.txt",
    size: "92 KB",
    kind: "TXT",
    tag: "专家访谈",
    partition: "SOURCE",
    locator: "1,426 行",
    status: "READY",
  },
];

export const sourceRefs: SourceRef[] = [
  {
    id: "SRC-001",
    source: "商业银行金融资产风险分类办法.pdf",
    locator: "第 11 页 · 第 14 条",
    excerpt: "金融资产逾期后应至少归为关注类；逾期超过九十天应至少归为次级类。",
  },
  {
    id: "SRC-017",
    source: "行内风险分类实施细则.docx",
    locator: "表格 4 · 第 6 行",
    excerpt: "对担保代偿能力充分且还款来源明确的业务，须由风险经理说明例外依据。",
  },
  {
    id: "SRC-032",
    source: "风险经理访谈记录.txt",
    locator: "第 388–410 行",
    excerpt: "现金流持续恶化时，不能仅因为抵押物足值维持正常分类。",
  },
];

export const initialMarkdown = `# 偿债能力与担保因素综合研判

## 适用边界
适用于对公贷款在逾期天数规则之外，需要综合偿债能力和担保因素判断分类级别的场景。[SRC-001]

## 规则 R-017：现金流恶化优先
**条件**：经营现金流连续两个季度为负，且主营收入同比下降超过 30%。

**结论**：不得仅凭抵押物足值维持正常类，应进入关注类复核。[SRC-032]

**例外**：存在可核验的非经营性一次性支出，且未来三个月回款已经形成无条件合同。

## 规则 R-021：担保例外须留痕
担保代偿能力充分只可作为辅助因素。使用例外时，风险经理必须记录担保人现金流、代偿意愿和处置周期。[SRC-017]

## 研判流程
1. 先执行逾期与重组硬规则。
2. 再评估第一还款来源是否持续恶化。
3. 最后检查担保是否构成有证据的例外，不得倒置判断顺序。
`;

export const assets: Asset[] = [
  {
    id: "rules",
    name: "规则清单",
    format: "XLSX · JSON",
    description: "稳定规则 ID、条件、结论、优先级、例外与来源。",
    state: "READY",
    version: "asset-rules-07",
    sourceRevision: "rev-07",
  },
  {
    id: "flow",
    name: "思维链",
    format: "MD · JSON · Mermaid",
    description: "可审计的专家研判流程；不包含模型私有推理过程。",
    state: "READY",
    version: "asset-flow-07",
    sourceRevision: "rev-07",
  },
  {
    id: "skill",
    name: "Skill 包",
    format: "ZIP · Manifest",
    description: "SKILL.md、规则、流程、Prompt、few-shot 与 Schema。首发仅作为资源包。",
    state: "READY",
    version: "asset-skill-07",
    sourceRevision: "rev-07",
  },
  {
    id: "qa",
    name: "QA 对",
    format: "JSONL",
    description: "带来源引用、通过 Schema 校验与去重的问答语料。",
    state: "READY",
    version: "asset-qa-07",
    sourceRevision: "rev-07",
  },
  {
    id: "evaluation",
    name: "评测集",
    format: "JSONL",
    description: "只读取留出分区，绝不进入萃取 Prompt 或 QA 生成。",
    state: "BLOCKED",
    version: "尚未生成",
    sourceRevision: "rev-07",
    detail: "当前子场景未绑定足够的留出标注案例",
  },
];

export const agents: AgentConfig[] = [
  ["discover", "环节一", "场景探索智能体", "上传探索素材", "识别业务主题与候选萃取场景。", "通用场景探索 v1.0", "Qwen2.5-72B", "输出稳定性", "平衡", "cfg-3"],
  ["extract", "环节二", "知识萃取智能体", "开始萃取", "从来源 Chunk 生成带引用的 KnowledgeIR 与 Markdown。", "对公贷款·知识萃取 v1.1", "Qwen2.5-72B", "输出稳定性", "严谨", "cfg-8"],
  ["align", "环节二", "冲突检测与对齐智能体", "AI 对齐", "生成结构化 Proposal，不直接覆盖人工 Revision。", "对公贷款·冲突检测 v1.0", "DeepSeek-V3", "输出稳定性", "严谨", "cfg-5"],
  ["rules", "环节三", "规则库生成智能体", "生成资产", "从定稿 Revision 确定性生成规则清单。", "通用规则库生成 v2.0", "DeepSeek-V3", "输出格式", "XLSX + JSON", "cfg-4"],
  ["flow", "环节三", "思维链生成智能体", "生成资产", "生成可审计研判流程，不暴露私有推理。", "通用研判流程生成 v2.0", "Qwen2.5-72B", "输出格式", "MD + JSON", "cfg-4"],
  ["skill", "环节三", "Skill 生成智能体", "生成资产", "打包只读 Skill 资源及其不可变 Manifest。", "通用 Skill 打包 v1.3", "Qwen2.5-72B", "安全模式", "不执行脚本", "cfg-6"],
  ["qa", "环节三", "QA 与评测集智能体", "生成资产", "按素材分区分别构造 QA 和留出评测集。", "通用 QA/评测 v1.4", "DeepSeek-V3", "数据隔离", "强制", "cfg-7"],
].map(([id, stage, name, trigger, description, skill, model, optionLabel, option, version]) => ({
  id,
  stage,
  name,
  trigger,
  description,
  skill,
  model,
  optionLabel,
  option,
  version,
}));

export const skills: SkillSummary[] = [
  { id: "sk-1", name: "规则萃取", description: "抽取 IF/THEN 判据、优先级、例外和来源的通用骨架。", kind: "TEMPLATE", version: "v2.0", packageHash: "sha256:41f3…9b21" },
  { id: "sk-2", name: "研判流程萃取", description: "将专家判断拆成可审计节点、分支和回退条件。", kind: "TEMPLATE", version: "v2.0", packageHash: "sha256:69b0…a713" },
  { id: "sk-3", name: "冲突检测", description: "识别规则冲突、口径差异和缺失依据，输出 Proposal。", kind: "TEMPLATE", version: "v1.2", packageHash: "sha256:d3a8…00ce" },
  { id: "sk-4", name: "对公贷款·知识萃取", description: "面向五级分类的规则与研判流程场景实例。", kind: "INSTANCE", version: "v1.1", parent: "规则萃取 v2.0", scene: "对公贷款五级分类", packageHash: "sha256:2ce8…7fd0" },
  { id: "sk-5", name: "对公贷款·冲突检测", description: "按监管依据优先级检查分类口径和例外。", kind: "INSTANCE", version: "v1.0", parent: "冲突检测 v1.2", scene: "对公贷款五级分类", packageHash: "sha256:fa60…d23c" },
];
