package com.knowledgemeltingpot.workbench.worker.agent;

import com.knowledgemeltingpot.workbench.application.port.KnowledgeAlignmentWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.AssetGenerationWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.SceneExplorationWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.SkillEvaluationWorkflowPort;
import com.knowledgemeltingpot.workbench.domain.ExplorationCandidate;
import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicitly test-only deterministic workflow used by local Compose E2E. It is
 * disabled by default and never reports model accuracy or provider connectivity.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "workbench.agent.test-stub-enabled", havingValue = "true")
public class DeterministicWorkflowTestConfiguration {
    @Bean
    @ConditionalOnMissingBean(KnowledgeExtractionWorkflowPort.class)
    KnowledgeExtractionWorkflowPort deterministicExtractionWorkflow() {
        return new KnowledgeExtractionWorkflowPort() {
            @Override
            public KnowledgeDraft map(MapRequest request) {
                String summary = request.content().strip().replaceAll("\\s+", " ");
                if (summary.length() > 300) summary = summary.substring(0, 300);
                RuleDraft rule = new RuleDraft("来源规则 " + request.sourceRefCode(),
                        "依据来源 " + request.sourceRefCode(), summary, 100, List.of(),
                        List.of(request.sourceRefCode()));
                return new KnowledgeDraft(List.of(rule), List.of(), List.of(), List.of());
            }

            @Override
            public KnowledgeDraft reduce(ReduceRequest request) {
                Map<String, RuleDraft> rules = new LinkedHashMap<>();
                request.mapResults().stream().flatMap(result -> result.rules().stream())
                        .forEach(rule -> rules.putIfAbsent(rule.condition() + "\n" + rule.conclusion(), rule));
                return new KnowledgeDraft(List.copyOf(rules.values()), List.of(), List.of(), List.of());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeAlignmentWorkflowPort.class)
    KnowledgeAlignmentWorkflowPort deterministicAlignmentWorkflow() {
        return request -> {
            KnowledgeIr base = request.base();
            if (request.action() != AlignmentAction.REGULATORY || request.evidence().isEmpty()) {
                return new KnowledgeAlignmentWorkflowPort.AlignmentResult(base,
                        "本地确定性验收适配器完成结构与引用检查；未调用外部模型。");
            }
            var first = request.evidence().getFirst();
            List<KnowledgeIr.SourceRef> refs = new ArrayList<>(base.sourceRefs());
            request.evidence().stream().map(KnowledgeAlignmentWorkflowPort.Evidence::sourceRef)
                    .filter(ref -> !refs.contains(ref)).forEach(refs::add);
            refs.sort(Comparator.comparing(KnowledgeIr.SourceRef::code));
            List<KnowledgeIr.Rule> rules = new ArrayList<>(base.rules());
            rules.add(new KnowledgeIr.Rule("R-0000000000000000", "监管依据补充",
                    "监管素材适用于当前子场景", "按监管来源执行补充检查", 10, List.of(),
                    List.of(first.sourceRef().code())));
            KnowledgeIr replacement = new KnowledgeIr(base.schemaVersion(), base.metadata(), rules, base.flows(),
                    base.conflicts(), base.gaps(), refs);
            return new KnowledgeAlignmentWorkflowPort.AlignmentResult(replacement,
                    "根据用户明确指定的监管依据补充一条可追溯规则。");
        };
    }

    @Bean
    @ConditionalOnMissingBean(AssetGenerationWorkflowPort.class)
    AssetGenerationWorkflowPort deterministicAssetGenerationWorkflow() {
        return request -> {
            if (request.assetType() == com.knowledgemeltingpot.workbench.domain.AssetType.EVALUATION_SET) {
                List<AssetGenerationWorkflowPort.DraftItem> items = request.holdoutSources().stream()
                        .map(source -> new AssetGenerationWorkflowPort.DraftItem(source.materialId().toString(),
                                "独立留出评测", "", List.of(source.materialId().toString()),
                                List.of("LABELED_HOLDOUT")))
                        .toList();
                return new AssetGenerationWorkflowPort.AssetDraft("本地验收留出登记", items);
            }
            String source = request.sourceRefCodes().isEmpty() ? "SRC-MISSING" : request.sourceRefCodes().getFirst();
            var item = switch (request.assetType()) {
                case RULE_CATALOG -> new AssetGenerationWorkflowPort.DraftItem("R001", "分类规则",
                        "按定稿知识执行条件判断与分类结论。", List.of(source), List.of("规则"));
                case DECISION_FLOW -> new AssetGenerationWorkflowPort.DraftItem("S001", "核验并分类",
                        "核验输入条件、例外与来源后给出可审计结论。", List.of(source), List.of("流程"));
                case SKILL_PACKAGE -> new AssetGenerationWorkflowPort.DraftItem("P001", "受控判断模块",
                        "仅使用提供的定稿知识和来源锚点生成结构化结果，禁止执行脚本。", List.of(source),
                        List.of("RESOURCE_ONLY"));
                case QA_PAIRS -> new AssetGenerationWorkflowPort.DraftItem("Q001", "如何执行分类？",
                        "依据定稿规则核验条件和例外，并保留来源锚点。", List.of(source), List.of("QA"));
                case EVALUATION_SET -> throw new IllegalStateException("handled above");
            };
            return new AssetGenerationWorkflowPort.AssetDraft("本地确定性资产验收结果", List.of(item));
        };
    }

    @Bean
    @ConditionalOnMissingBean(SceneExplorationWorkflowPort.class)
    SceneExplorationWorkflowPort deterministicSceneExplorationWorkflow() {
        return request -> {
            List<String> sourceCodes = request.sources().stream()
                    .map(SceneExplorationWorkflowPort.ExplorationSource::sourceCode).toList();
            String sourceNames = request.sources().stream()
                    .map(SceneExplorationWorkflowPort.ExplorationSource::fileName)
                    .collect(java.util.stream.Collectors.joining("、"));
            var candidate = new SceneExplorationWorkflowPort.CandidateDraft(1, "授信资料风险识别",
                    "由 staging 素材识别出的本地验收候选场景。", "异常线索与审批红线",
                    "聚合规则、例外与审批边界。", "候选直接来自已验证素材：" + sourceNames,
                    ExplorationCandidate.ValueLevel.HIGH, Math.max(1, request.sources().size() * 3),
                    Math.max(1, request.sources().size()), List.of("授信", "风险识别"), sourceCodes);
            return new SceneExplorationWorkflowPort.ExplorationResult(List.of(candidate));
        };
    }

    @Bean
    @ConditionalOnMissingBean(SkillEvaluationWorkflowPort.class)
    SkillEvaluationWorkflowPort deterministicSkillEvaluationWorkflow() {
        return request -> {
            String input = request.input().replaceAll("\\s+", "");
            String prediction;
            if (input.contains("逾期120") || input.contains("严重减值") || input.contains("重组失败")) {
                prediction = "次级";
            } else if (input.contains("逾期30") || input.contains("还款能力下降")) {
                prediction = "关注";
            } else {
                prediction = "正常";
            }
            return new SkillEvaluationWorkflowPort.EvaluationPrediction(prediction);
        };
    }
}
