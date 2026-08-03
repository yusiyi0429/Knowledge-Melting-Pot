package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionEventType;
import com.openjiuwen.core.session.stream.CustomSchema;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.TraceSchema;

import java.util.Map;

final class SdkStreamEventMapper {
    MappedSdkEvent map(Object rawEvent) {
        if (rawEvent instanceof OutputSchema output) {
            return mapOutput(output.getType(), output.getPayload());
        }
        if (rawEvent instanceof TraceSchema trace) {
            return new MappedSdkEvent(AgentExecutionEventType.PROGRESS, "", safeCode(trace.getType()));
        }
        if (rawEvent instanceof CustomSchema) {
            return new MappedSdkEvent(AgentExecutionEventType.PROGRESS, "", "custom");
        }
        return new MappedSdkEvent(AgentExecutionEventType.PROGRESS, "", "sdk_event");
    }

    private MappedSdkEvent mapOutput(String rawType, Object payload) {
        String type = rawType == null ? "" : rawType;
        return switch (type) {
            case "llm_output" -> mapModelOutput(payload);
            case "llm_reasoning" ->
                    new MappedSdkEvent(AgentExecutionEventType.PROGRESS, "", "model_reasoning");
            case "llm_usage" ->
                    new MappedSdkEvent(AgentExecutionEventType.PROGRESS, "", "model_usage");
            case "answer", "workflow_final", "end node stream" ->
                    new MappedSdkEvent(
                            AgentExecutionEventType.COMPLETED,
                            SdkValueRenderer.render(payload),
                            "completed");
            case "__interaction__" ->
                    new MappedSdkEvent(
                            AgentExecutionEventType.INPUT_REQUIRED,
                            "需要补充输入后继续执行",
                            "input_required");
            case "error" ->
                    new MappedSdkEvent(
                            AgentExecutionEventType.FAILED,
                            SensitiveTextRedactor.redact(SdkValueRenderer.render(payload)),
                            "sdk_error");
            default -> new MappedSdkEvent(AgentExecutionEventType.PROGRESS, "", safeCode(type));
        };
    }

    private MappedSdkEvent mapModelOutput(Object payload) {
        if (payload instanceof Map<?, ?> map && map.containsKey("tool_calls")) {
            return new MappedSdkEvent(
                    AgentExecutionEventType.TOOL_ACTIVITY,
                    "模型请求执行受控工具",
                    "tool_call");
        }
        return new MappedSdkEvent(
                AgentExecutionEventType.TEXT_DELTA,
                SdkValueRenderer.render(payload),
                "text_delta");
    }

    private static String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "sdk_event";
        }
        String normalized = value.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }
}
