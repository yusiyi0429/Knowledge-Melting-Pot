package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.application.port.SceneExplorationWorkflowPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workbench.agent.enabled", havingValue = "true")
public class AgentSceneExplorationWorkflowAdapter implements SceneExplorationWorkflowPort {
    private final VersionedAgentExecutor agent;
    private final ObjectMapper objectMapper;

    public AgentSceneExplorationWorkflowAdapter(VersionedAgentExecutor agent, ObjectMapper objectMapper) {
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExplorationResult explore(ExplorationRequest request) {
        String prompt = """
                你是场景探索阶段。仅根据下方已验证的 staging 素材，识别 1 到 5 个值得进入正式知识萃取流程的候选。
                不得使用互联网或外部事实，不得输出模型私有推理过程。返回单个 JSON 对象，唯一顶层字段为 candidates。
                每个候选字段严格为 rank、sceneName、sceneDescription、subSceneName、subSceneDescription、
                rationale、valueLevel、estimatedRuleCount、estimatedFlowCount、tags、materialIds。
                valueLevel 仅可为 HIGH、MEDIUM、LOW；materialIds 只能使用输入中出现的 UUID，且至少一个。
                rationale 是简短、可审计的业务依据，不是思维链。

                Staging 素材：
                %s
                """.formatted(toJson(request.sources()));
        AgentExecutionResult result = agent.stream(request.modelConfigVersionId(), request.skillVersionId(),
                new AgentExecutionRequest(request.sessionId() + ":scene-explore", "system", prompt,
                        AgentExecutionMode.WORKFLOW), ignored -> { });
        if (result.status() != AgentExecutionStatus.COMPLETED) {
            throw new AgentKnowledgeExtractionWorkflowAdapter.WorkflowGenerationException(
                    result.failureCode().isBlank() ? result.status().name() : result.failureCode());
        }
        try {
            return objectMapper.readValue(jsonBody(result.output()), ExplorationResult.class);
        } catch (JsonProcessingException exception) {
            throw new AgentKnowledgeExtractionWorkflowAdapter.WorkflowGenerationException("MODEL_JSON_INVALID");
        }
    }

    private String jsonBody(String value) {
        String text = value == null ? "" : value.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) return text.substring(firstLine + 1, closing).strip();
        }
        return text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("exploration sources could not be serialized", exception);
        }
    }
}
