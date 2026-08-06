package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort.KnowledgeDraft;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort.MapRequest;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort.ReduceRequest;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort.RuleDraft;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentKnowledgeExtractionWorkflowAdapterTest {
    private static final String VALID = """
            {"rules":[{"title":"逾期分类","condition":"逾期超过30天","conclusion":"进入关注类",
            "priority":100,"exceptions":[],"sourceRefs":["SRC-001"]}],
            "flows":[],"conflicts":[],"gaps":[]}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentKnowledgeExtractionWorkflowAdapter adapter =
            new AgentKnowledgeExtractionWorkflowAdapter(null, objectMapper);

    @Test
    void schemaIsValidAndRequiresAllTopLevelCollections() throws Exception {
        var schema = objectMapper.readTree(AgentKnowledgeExtractionWorkflowAdapter.KNOWLEDGE_DRAFT_SCHEMA);

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("required")).hasSize(4);
        assertThat(schema.path("properties").path("rules").path("items")
                .path("required")).hasSize(6);
    }

    @Test
    void parsesPlainFencedPrefixedThinkingAndWrappedModelJson() throws Exception {
        KnowledgeDraft plain = adapter.parseOutput(VALID);
        KnowledgeDraft fenced = adapter.parseOutput("```json\n" + VALID + "\n```");
        KnowledgeDraft prefixed = adapter.parseOutput("以下是结果：\n" + VALID);
        KnowledgeDraft thinking = adapter.parseOutput(
                "<think>模型内部分析包含示例 {\"example\":true}，不得持久化</think>\n" + VALID);
        KnowledgeDraft wrapped = adapter.parseOutput("""
                {"answer":%s}
                """.formatted(VALID));

        assertThat(plain.rules()).hasSize(1);
        assertThat(fenced).isEqualTo(plain);
        assertThat(prefixed).isEqualTo(plain);
        assertThat(thinking).isEqualTo(plain);
        assertThat(wrapped).isEqualTo(plain);
    }

    @Test
    void rejectsMalformedOutput() {
        assertThatThrownBy(() -> adapter.parseOutput("不是 JSON"))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void toleratesCommonModelJsonSyntaxNoiseWithoutRelaxingRequiredFields() throws Exception {
        KnowledgeDraft trailingComma = adapter.parseOutput("""
                {"rules":[],"flows":[],"conflicts":[],"gaps":[],}
                """);
        KnowledgeDraft literalNewline = adapter.parseOutput("""
                {"rules":[{"title":"第一行
                第二行","condition":"条件","conclusion":"结论","priority":1,
                "exceptions":[],"sourceRefs":["SRC-001"]}],
                "flows":[],"conflicts":[],"gaps":[]}
                """);

        assertThat(trailingComma.rules()).isEmpty();
        assertThat(literalNewline.rules()).hasSize(1);
        assertThatThrownBy(() -> adapter.parseOutput("{\"rules\":[]}"))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void skipsFragmentsThatCannotCarryACompleteRuleWithoutCallingTheModel() {
        VersionedAgentExecutor executor = mock(VersionedAgentExecutor.class);
        AgentKnowledgeExtractionWorkflowAdapter workflow =
                new AgentKnowledgeExtractionWorkflowAdapter(executor, objectMapper);

        KnowledgeDraft result = workflow.map(new MapRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "SRC-001", "DOCX_TABLE_CELL", "正常类"));

        assertThat(result.rules()).isEmpty();
        assertThat(result.flows()).isEmpty();
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.gaps()).isEmpty();
        verifyNoInteractions(executor);
    }

    @Test
    void retriesMalformedProviderJsonUpToTheBoundedLimit() {
        VersionedAgentExecutor executor = mock(VersionedAgentExecutor.class);
        UUID modelVersionId = UUID.randomUUID();
        UUID skillVersionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-05T10:00:00Z");
        AgentExecutionResult malformed = new AgentExecutionResult("job", "session",
                AgentExecutionStatus.COMPLETED, "{invalid}", "", "", now, now.plusSeconds(1));
        AgentExecutionResult valid = new AgentExecutionResult("job", "session",
                AgentExecutionStatus.COMPLETED, VALID, "", "", now, now.plusSeconds(1));
        when(executor.stream(eq(modelVersionId), eq(skillVersionId), any(AgentExecutionRequest.class), any()))
                .thenReturn(malformed, malformed, malformed, valid);
        AgentKnowledgeExtractionWorkflowAdapter workflow =
                new AgentKnowledgeExtractionWorkflowAdapter(executor, objectMapper);

        KnowledgeDraft result = workflow.map(new MapRequest(UUID.randomUUID(), modelVersionId,
                skillVersionId, "SRC-001", "DOCX_PARAGRAPH", "逾期超过三十天时进入重点复核"));

        assertThat(result.rules()).hasSize(1);
        ArgumentCaptor<AgentExecutionRequest> requests = ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(executor, times(4)).stream(eq(modelVersionId), eq(skillVersionId), requests.capture(), any());
        assertThat(requests.getAllValues()).extracting(AgentExecutionRequest::workspaceId)
                .containsExactly(
                        requests.getAllValues().getFirst().workspaceId(),
                        requests.getAllValues().getFirst().workspaceId() + ":format-retry-2",
                        requests.getAllValues().getFirst().workspaceId() + ":format-retry-3",
                        requests.getAllValues().getFirst().workspaceId() + ":format-retry-4");
    }

    @Test
    void deterministicallyReducesMapFactsWithoutAnotherModelCall() {
        KnowledgeDraft first = new KnowledgeDraft(
                List.of(new RuleDraft("规则 A", " 逾期超过30天 ", "进入关注类", 10,
                        List.of("例外 A"), List.of("SRC-001"))),
                List.of(),
                List.of(new KnowledgeIr.Conflict("C-model-1", "口径冲突", List.of("SRC-001"))),
                List.of(new KnowledgeIr.Gap("G-model-1", "缺少审批材料")));
        KnowledgeDraft second = new KnowledgeDraft(
                List.of(new RuleDraft("规则 B", "逾期超过30天", "进入关注类", 20,
                        List.of("例外 B"), List.of("SRC-002"))),
                List.of(),
                List.of(new KnowledgeIr.Conflict("C-model-2", "口径冲突", List.of("SRC-002"))),
                List.of(new KnowledgeIr.Gap("G-model-2", "缺少审批材料")));

        KnowledgeDraft reduced = adapter.reduce(new ReduceRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), List.of(first, second)));

        assertThat(reduced.rules()).hasSize(1);
        assertThat(reduced.rules().getFirst().priority()).isEqualTo(20);
        assertThat(reduced.rules().getFirst().sourceRefs()).containsExactly("SRC-001", "SRC-002");
        assertThat(reduced.conflicts()).hasSize(1);
        assertThat(reduced.conflicts().getFirst().sourceRefs()).containsExactly("SRC-001", "SRC-002");
        assertThat(reduced.gaps()).hasSize(1);
    }
}
