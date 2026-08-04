package com.knowledgemeltingpot.workbench.domain;

public enum AgentRole {
    SCENE_EXPLORER("场景探索智能体", "环节一", "识别候选场景并整理素材边界"),
    KNOWLEDGE_EXTRACTOR("知识萃取智能体", "环节二", "从可信素材生成带来源的 KnowledgeIR"),
    ALIGNMENT_REVIEWER("冲突检测与对齐智能体", "环节二", "生成可审计的冲突与对齐提案"),
    RULE_CATALOG_GENERATOR("规则库生成智能体", "环节三", "从定稿 Revision 生成规则清单"),
    DECISION_FLOW_GENERATOR("思维链生成智能体", "环节三", "生成可审计的研判流程，不暴露模型私有思维链"),
    SKILL_PACKAGER("Skill 生成智能体", "环节三", "生成只包含提示词与资源的 Skill 包"),
    QA_EVALUATOR("QA 与评测集智能体", "环节三", "生成 QA 数据并隔离留出集评测");

    private final String displayName;
    private final String stage;
    private final String description;

    AgentRole(String displayName, String stage, String description) {
        this.displayName = displayName;
        this.stage = stage;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String stage() {
        return stage;
    }

    public String description() {
        return description;
    }
}
