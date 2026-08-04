package com.knowledgemeltingpot.workbench.worker.agent;

import com.knowledgemeltingpot.workbench.application.port.KnowledgeAlignmentWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort;
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
}
