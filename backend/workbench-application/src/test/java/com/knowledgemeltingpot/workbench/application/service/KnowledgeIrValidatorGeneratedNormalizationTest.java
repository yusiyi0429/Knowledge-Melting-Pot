package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIrValidatorGeneratedNormalizationTest {
    private final KnowledgeIrValidator validator =
            new KnowledgeIrValidator(new ObjectMapper().findAndRegisterModules());

    @Test
    void replacesModelIdsDeduplicatesEvidenceAndDropsUnsupportedGraphReferences() {
        UUID documentId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String source = "SRC-TEST-1";
        KnowledgeIr.Rule duplicate = new KnowledgeIr.Rule("model-rule", "规则", "条件", "结论", 20,
                List.of("例外"), List.of(source));
        KnowledgeIr.Flow flow = new KnowledgeIr.Flow("模型流程", "处置流程",
                List.of(new KnowledgeIr.FlowNode("开始", "开始", true, List.of(source, source)),
                        new KnowledgeIr.FlowNode("结束", "结束", true, List.of())),
                List.of(new KnowledgeIr.FlowEdge("边", "开始", "结束", "下一步"),
                        new KnowledgeIr.FlowEdge("坏边", "不存在", "结束", "忽略")));
        KnowledgeIr ir = new KnowledgeIr(KnowledgeIr.SCHEMA_VERSION,
                new KnowledgeIr.Metadata(documentId, subSceneId, roundId, "a".repeat(64)),
                List.of(duplicate, duplicate), List.of(flow),
                List.of(new KnowledgeIr.Conflict("冲突", "无证据冲突", List.of())),
                List.of(new KnowledgeIr.Gap("缺口", "需要补充材料")),
                List.of(new KnowledgeIr.SourceRef(source, materialId, "b".repeat(64), chunkId,
                        "TXT_LINES", null, null, null, null, null, null, null, null, 1, 2,
                        "c".repeat(64))));

        KnowledgeIr normalized = validator.validate(validator.normalizeGenerated(ir));

        assertThat(normalized.rules()).hasSize(1);
        assertThat(normalized.rules().getFirst().id()).matches("R-[a-f0-9]{16}");
        assertThat(normalized.flows().getFirst().id()).matches("F-[a-f0-9]{16}");
        assertThat(normalized.flows().getFirst().nodes()).allMatch(node -> node.id().matches("N-[a-f0-9]{16}"));
        assertThat(normalized.flows().getFirst().edges()).hasSize(1)
                .allMatch(edge -> edge.id().matches("E-[a-f0-9]{16}"));
        assertThat(normalized.flows().getFirst().nodes().get(1).critical()).isFalse();
        assertThat(normalized.conflicts()).isEmpty();
        assertThat(normalized.gaps().getFirst().id()).matches("G-[a-f0-9]{16}");
    }
}
