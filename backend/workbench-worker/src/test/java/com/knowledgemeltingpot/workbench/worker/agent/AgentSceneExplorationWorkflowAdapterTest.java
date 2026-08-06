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
import com.knowledgemeltingpot.workbench.application.port.SceneExplorationWorkflowPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class AgentSceneExplorationWorkflowAdapterTest {
    private static final String VALID = """
            {"candidates":[{"rank":4,"sceneName":"贷款分类","sceneDescription":"制度场景",
            "subSceneName":"逾期分类","subSceneDescription":"标签规则","rationale":"来自 MAT-01",
            "valueLevel":"HIGH","estimatedRuleCount":8,"estimatedFlowCount":2,
            "tags":["贷款"],"sourceCodes":["MAT-01"]}]}
            """;

    @Test
    void repairsOneInvalidResultAndReturnsNormalizedCandidate() {
        VersionedAgentExecutor executor = mock(VersionedAgentExecutor.class);
        UUID model = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        when(executor.stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any()))
                .thenReturn(completed(VALID.replace("MAT-01", "MAT-99")), completed(VALID));
        var adapter = new AgentSceneExplorationWorkflowAdapter(executor, new ObjectMapper(),
                new ExplorationResultValidator());

        var result = adapter.explore(request(model, skill));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().rank()).isEqualTo(1);
        assertThat(result.candidates().getFirst().sourceCodes()).containsExactly("MAT-01");
        verify(executor, times(2)).stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any());
    }

    @Test
    void failsWithSpecificCodeAfterTheSingleRepairAttempt() {
        VersionedAgentExecutor executor = mock(VersionedAgentExecutor.class);
        UUID model = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        when(executor.stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any()))
                .thenReturn(completed(VALID.replace("MAT-01", "MAT-99")));
        var adapter = new AgentSceneExplorationWorkflowAdapter(executor, new ObjectMapper(),
                new ExplorationResultValidator());

        assertThatThrownBy(() -> adapter.explore(request(model, skill)))
                .isInstanceOfSatisfying(AgentKnowledgeExtractionWorkflowAdapter.WorkflowGenerationException.class,
                        error -> assertThat(error.code()).isEqualTo("EXPLORATION_SOURCE_REFERENCE_INVALID"));
        verify(executor, times(2)).stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any());
    }

    @Test
    void repairForEmptyCandidatesIncludesTheTrustedSourcesAgain() {
        VersionedAgentExecutor executor = mock(VersionedAgentExecutor.class);
        UUID model = UUID.randomUUID();
        UUID skill = UUID.randomUUID();
        when(executor.stream(eq(model), eq(skill), any(AgentExecutionRequest.class), any()))
                .thenReturn(completed("{\"candidates\":[]}"), completed(VALID));
        var adapter = new AgentSceneExplorationWorkflowAdapter(executor, new ObjectMapper(),
                new ExplorationResultValidator());

        var result = adapter.explore(request(model, skill));

        assertThat(result.candidates()).hasSize(1);
        ArgumentCaptor<AgentExecutionRequest> requestCaptor = ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(executor, times(2)).stream(eq(model), eq(skill), requestCaptor.capture(), any());
        AgentExecutionRequest repair = requestCaptor.getAllValues().get(1);
        assertThat(repair.prompt())
                .contains("EXPLORATION_CANDIDATES_EMPTY")
                .contains("可信 Staging 素材")
                .contains("逾期规则")
                .contains("不能继续返回空 candidates");
    }

    private SceneExplorationWorkflowPort.ExplorationRequest request(UUID model, UUID skill) {
        return new SceneExplorationWorkflowPort.ExplorationRequest(UUID.randomUUID(), model, skill,
                List.of(new SceneExplorationWorkflowPort.ExplorationSource(UUID.randomUUID(), "MAT-01", "制度.txt",
                        List.of(new SceneExplorationWorkflowPort.ExplorationChunk("SRC-1", "{}", "逾期规则")))));
    }

    private AgentExecutionResult completed(String output) {
        Instant now = Instant.parse("2026-08-06T04:00:00Z");
        return new AgentExecutionResult("job", "session", AgentExecutionStatus.COMPLETED, output, "", "", now,
                now.plusSeconds(1));
    }
}
