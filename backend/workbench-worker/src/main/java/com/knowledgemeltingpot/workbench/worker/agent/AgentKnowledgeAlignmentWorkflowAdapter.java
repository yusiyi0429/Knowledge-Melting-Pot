package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.agent.KnowledgeExtractionPort;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeAlignmentWorkflowPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workbench.agent.enabled", havingValue = "true")
public class AgentKnowledgeAlignmentWorkflowAdapter implements KnowledgeAlignmentWorkflowPort {
    private final KnowledgeExtractionPort agent;
    private final ObjectMapper objectMapper;

    public AgentKnowledgeAlignmentWorkflowAdapter(KnowledgeExtractionPort agent, ObjectMapper objectMapper) {
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    @Override
    public AlignmentResult generate(AlignmentRequest request) {
        String prompt = """
                你是 KnowledgeIR 对齐阶段。动作：%s。
                只可修改给定 KnowledgeIR；REGULATORY 动作只能引用给定监管证据，其他动作不得引入新来源。
                必须保留 metadata。返回单个 JSON 对象，字段严格为 replacement 和 reason。
                replacement 必须符合 KnowledgeIR v1；reason 是简洁可审计原因，不得输出模型私有推理过程。

                基线 KnowledgeIR：
                %s

                可用监管证据：
                %s
                """.formatted(request.action(), toJson(request.base()), toJson(request.evidence()));
        AgentExecutionResult result = agent.stream(new AgentExecutionRequest(
                request.jobId().toString() + ":alignment", "system", prompt, AgentExecutionMode.WORKFLOW),
                ignored -> { });
        if (result.status() != AgentExecutionStatus.COMPLETED) {
            throw new AgentKnowledgeExtractionWorkflowAdapter.WorkflowGenerationException(
                    result.failureCode().isBlank() ? result.status().name() : result.failureCode());
        }
        try {
            return objectMapper.readValue(jsonBody(result.output()), AlignmentResult.class);
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
            throw new IllegalStateException("alignment prompt data could not be serialized", exception);
        }
    }
}
