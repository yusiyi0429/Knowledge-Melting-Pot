package com.knowledgemeltingpot.workbench.api.security;

import com.knowledgemeltingpot.workbench.application.port.UserRepository;
import com.knowledgemeltingpot.workbench.application.service.SkillService;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ensures every governed Agent role has a safe, resource-only Skill template.
 * The operation is idempotent, preserves user-created templates and never
 * executes package code.
 */
@Component
public class DefaultSkillBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSkillBootstrap.class);
    private static final List<BuiltInSkill> BUILT_IN_SKILLS = List.of(
            skill(AgentRole.SCENE_EXPLORER, "场景探索基础模板",
                    "从暂存素材识别候选场景、业务边界和待补信息。",
                    "仅分析提供的暂存素材，提议候选场景、子场景、业务边界与待补信息。每项提议都要说明依据，不得将候选结论写成已确认事实。",
                    "staging-materials", "scene-candidate-schema"),
            skill(AgentRole.KNOWLEDGE_EXTRACTOR, "知识萃取基础模板",
                    "从已验证素材中提取规则、条件、例外、流程与来源，生成可审计的 KnowledgeIR。",
                    "仅基于提供的已验证素材提取规则、条件、例外、流程与来源。信息不足时明确标记缺失，不得补造事实；所有结论必须保留来源引用。",
                    "verified-materials", "knowledge-ir-schema"),
            skill(AgentRole.ALIGNMENT_REVIEWER, "冲突检测与对齐基础模板",
                    "检测 Revision 内部及监管依据之间的冲突，并生成可审计的对齐提案。",
                    "比较当前 Revision 与已标记的监管依据，识别冲突、缺失和适用范围差异。只生成带 baseRevision、修改理由和来源引用的对齐提案，不直接修改已固化文档。",
                    "current-revision", "regulatory-materials", "alignment-proposal-schema"),
            skill(AgentRole.RULE_CATALOG_GENERATOR, "规则库生成基础模板",
                    "从定稿 Revision 生成结构化、可追溯的规则清单。",
                    "仅从定稿 Revision 生成规则清单。保留稳定规则 ID、条件、结论、优先级、例外和来源引用；不得引入 Revision 之外的规则。",
                    "finalized-revision", "rule-catalog-schema"),
            skill(AgentRole.DECISION_FLOW_GENERATOR, "研判流程生成基础模板",
                    "从定稿 Revision 生成可审计的业务研判流程。",
                    "把定稿 Revision 转换为可审计的研判步骤、判断条件、例外分支和来源引用。输出业务判定流程，不生成或暴露模型私有思维链。",
                    "finalized-revision", "decision-flow-schema", "mermaid-profile"),
            skill(AgentRole.SKILL_PACKAGER, "Skill 打包基础模板",
                    "从定稿 Revision 生成不含可执行脚本的 Skill 资源包。",
                    "根据定稿 Revision 组织 SKILL.md、规则、流程、Prompt、few-shot、Schema 和 Manifest。包内不得包含 Shell、Python、二进制或其他可执行内容，并为每个文件记录哈希。",
                    "finalized-revision", "resource-only-skill-schema", "package-manifest-schema"),
            skill(AgentRole.QA_EVALUATOR, "QA 与评测基础模板",
                    "生成带来源的 QA 数据，并保持留出评测数据物理隔离。",
                    "从允许的业务素材和定稿 Revision 生成带答案、来源和难度标签的 QA 数据，执行格式、去重和引用检查。LABELED_HOLDOUT 只能用于独立评测，不得进入萃取、对齐或训练样本生成。",
                    "finalized-revision", "qa-jsonl-schema", "holdout-isolation-policy"));

    private final SkillService skillService;
    private final UserRepository userRepository;
    private final String bootstrapUsername;

    public DefaultSkillBootstrap(SkillService skillService, UserRepository userRepository,
            @Value("${workbench.bootstrap-admin.username:admin}") String bootstrapUsername) {
        this.skillService = skillService;
        this.userRepository = userRepository;
        this.bootstrapUsername = bootstrapUsername;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public void createDefaultSkills() {
        Set<String> existingNames = skillService.list(SkillKind.TEMPLATE, null).stream()
                .filter(item -> item != null && item.skill() != null)
                .map(item -> item.skill().name())
                .collect(Collectors.toSet());
        List<BuiltInSkill> missing = BUILT_IN_SKILLS.stream()
                .filter(skill -> !existingNames.contains(skill.name()))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        userRepository.findByUsername(bootstrapUsername).ifPresentOrElse(actor -> {
            for (BuiltInSkill skill : missing) {
                skillService.createTemplate(skill.name(), skill.description(), skill.manifest(),
                        sha256(skill.manifest()), actor.id(), "bootstrap-agent-skill-" + skill.role().name().toLowerCase(),
                        "bootstrap-agent-skills");
            }
            LOGGER.info("Bootstrapped {} safe Agent Skill template(s).", missing.size());
        }, () -> LOGGER.warn("Built-in Agent Skills were not created because the bootstrap administrator is unavailable."));
    }

    private static BuiltInSkill skill(AgentRole role, String name, String description, String prompt,
            String... resources) {
        String resourceJson = java.util.Arrays.stream(resources)
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", "));
        String manifest = """
                {
                  "schemaVersion": "1.0",
                  "executionMode": "RESOURCE_ONLY",
                  "name": "%s",
                  "description": "%s",
                  "prompt": "%s",
                  "resources": [%s],
                  "metadata": {"agentRole": "%s", "builtIn": true}
                }
                """.formatted(name, description, prompt, resourceJson, role.name());
        return new BuiltInSkill(role, name, description, manifest);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record BuiltInSkill(AgentRole role, String name, String description, String manifest) {
    }
}
