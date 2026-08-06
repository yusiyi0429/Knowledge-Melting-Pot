package com.knowledgemeltingpot.workbench.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.application.port.AssetGenerationWorkflowPort;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentAssetGenerationWorkflowAdapterTest {
    @Test
    void repairsOneInvalidSourceReferenceAndReturnsTypedDraft() {
        VersionedAgentExecutor executor = mock(VersionedAgentExecutor.class);
        UUID model = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        when(executor.stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any()))
                .thenReturn(completed(ruleJson("SRC-999")), completed(ruleJson("SRC-001")));
        var adapter = new AgentAssetGenerationWorkflowAdapter(executor, new ObjectMapper());

        var result = adapter.generate(documentRequest(model, skill));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("R001");
            assertThat(item.sourceRefs()).containsExactly("SRC-001");
        });
        verify(executor, times(2)).stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any());
    }

    @Test
    void neverPlacesDocumentContentInEvaluationPrompt() {
        VersionedAgentExecutor executor = mock(VersionedAgentExecutor.class);
        UUID model = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        when(executor.stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any()))
                .thenReturn(completed("{\"summary\":\"留出登记\",\"items\":[{\"id\":\"" + materialId
                        + "\",\"title\":\"独立评测\",\"content\":\"\",\"sourceRefs\":[\"" + materialId
                        + "\"],\"tags\":[\"holdout\"]}]}"));
        var adapter = new AgentAssetGenerationWorkflowAdapter(executor, new ObjectMapper());

        var result = adapter.generate(new AssetGenerationWorkflowPort.AssetRequest(UUID.randomUUID(),
                AssetType.EVALUATION_SET, model, skill, "", List.of(),
                List.of(new AssetGenerationWorkflowPort.HoldoutSource(materialId, "a".repeat(64), "XLSX", 42))));

        assertThat(result.items()).singleElement().extracting(AssetGenerationWorkflowPort.DraftItem::content)
                .isEqualTo("");
        ArgumentCaptor<AgentExecutionRequest> request = ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(executor).stream(eq(model), eq(skill), request.capture(), any());
        assertThat(request.getValue().prompt()).contains(materialId.toString()).doesNotContain("定稿知识文档");
    }

    @Test
    void failsWithStableCodeAfterSingleRepairAttempt() {
        VersionedAgentExecutor executor = mock(VersionedAgentExecutor.class);
        UUID model = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        when(executor.stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any()))
                .thenReturn(completed("not-json"));
        var adapter = new AgentAssetGenerationWorkflowAdapter(executor, new ObjectMapper());

        assertThatThrownBy(() -> adapter.generate(documentRequest(model, skill)))
                .isInstanceOfSatisfying(AgentAssetGenerationWorkflowAdapter.WorkflowException.class,
                        error -> assertThat(error.code()).isEqualTo("ASSET_MODEL_JSON_INVALID"));
        verify(executor, times(2)).stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any());
    }

    private AssetGenerationWorkflowPort.AssetRequest documentRequest(UUID model, UUID skill) {
        return new AssetGenerationWorkflowPort.AssetRequest(UUID.randomUUID(), AssetType.RULE_CATALOG, model, skill,
                "# 分类规则\n\n[SRC-001] 事实依据", List.of("SRC-001"), List.of());
    }

    private String ruleJson(String source) {
        return "{\"summary\":\"规则\",\"items\":[{\"id\":\"R001\",\"title\":\"逾期分类\"," +
                "\"content\":\"按逾期天数分类\",\"sourceRefs\":[\"" + source + "\"],\"tags\":[\"规则\"]}]}";
    }

    private AgentExecutionResult completed(String output) {
        Instant now = Instant.parse("2026-08-06T04:00:00Z");
        return new AgentExecutionResult("job", "session", AgentExecutionStatus.COMPLETED, output, "", "", now,
                now.plusSeconds(1));
    }
}
