package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workbench.agent.enabled", havingValue = "true")
public class AgentKnowledgeExtractionWorkflowAdapter implements KnowledgeExtractionWorkflowPort {
    private final VersionedAgentExecutor agent;
    private final ObjectMapper objectMapper;

    public AgentKnowledgeExtractionWorkflowAdapter(VersionedAgentExecutor agent, ObjectMapper objectMapper) {
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    @Override
    public KnowledgeDraft map(MapRequest request) {
        String prompt = """
                你是知识萃取 Map 阶段。只能依据下方一个已验证来源，不得补充外部事实。
                返回单个 JSON 对象，字段严格为 rules、flows、conflicts、gaps。
                rules 每项字段为 title、condition、conclusion、priority、exceptions、sourceRefs；
                sourceRefs 只能使用给定来源编号。flows/conflicts/gaps 使用 KnowledgeIR v1 对应字段。
                不要返回 Markdown，不要解释，不要输出推理过程。

                来源编号：%s
                来源定位：%s
                来源正文：
                %s
                """.formatted(request.sourceRefCode(), request.locator(), request.content());
        return execute(request.modelConfigVersionId(), request.skillVersionId(),
                request.runId().toString() + ":map:" + request.sourceRefCode(), prompt);
    }

    @Override
    public KnowledgeDraft reduce(ReduceRequest request) {
        String prompt = """
                你是知识萃取 Reduce 阶段。对下方 Map JSON 做去重、合并，并保留冲突与缺失信息。
                只能使用已有 sourceRefs，不得补充外部事实。返回单个 JSON 对象，字段严格为
                rules、flows、conflicts、gaps；不要返回 Markdown、解释或推理过程。

                Map 结果：
                %s
                """.formatted(toJson(request.mapResults()));
        return execute(request.modelConfigVersionId(), request.skillVersionId(),
                request.runId().toString() + ":reduce", prompt);
    }

    private KnowledgeDraft execute(java.util.UUID modelConfigVersionId, java.util.UUID skillVersionId,
            String sessionId, String prompt) {
        AgentExecutionResult result = agent.stream(modelConfigVersionId, skillVersionId, new AgentExecutionRequest(
                sessionId, "system", prompt, AgentExecutionMode.WORKFLOW), ignored -> { });
        if (result.status() != AgentExecutionStatus.COMPLETED) {
            throw new WorkflowGenerationException(result.failureCode().isBlank()
                    ? result.status().name() : result.failureCode());
        }
        try {
            return objectMapper.readValue(jsonBody(result.output()), KnowledgeDraft.class);
        } catch (JsonProcessingException exception) {
            throw new WorkflowGenerationException("MODEL_JSON_INVALID");
        }
    }

    private String jsonBody(String value) {
        String text = value == null ? "" : value.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                return text.substring(firstLine + 1, closing).strip();
            }
        }
        return text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Map results could not be serialized", exception);
        }
    }

    public static final class WorkflowGenerationException extends RuntimeException {
        private final String code;

        WorkflowGenerationException(String code) {
            super("Agent workflow generation failed");
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
