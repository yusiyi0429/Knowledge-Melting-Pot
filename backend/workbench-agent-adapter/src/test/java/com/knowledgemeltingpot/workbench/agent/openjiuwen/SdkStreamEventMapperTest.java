package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionEventType;
import com.openjiuwen.core.session.stream.OutputSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SdkStreamEventMapperTest {
    private final SdkStreamEventMapper mapper = new SdkStreamEventMapper();

    @Test
    void mapsTextDeltaWithoutLeakingSdkPayloadType() {
        MappedSdkEvent mapped = mapper.map(new OutputSchema(
                "llm_output", 1, Map.of("content", "delta")));

        assertEquals(AgentExecutionEventType.TEXT_DELTA, mapped.type());
        assertEquals("delta", mapped.text());
        assertEquals("text_delta", mapped.code());
    }

    @Test
    void collapsesToolArgumentsToSafeBusinessEvent() {
        MappedSdkEvent mapped = mapper.map(new OutputSchema(
                "llm_output",
                2,
                Map.of("tool_calls", "password=do-not-leak")));

        assertEquals(AgentExecutionEventType.TOOL_ACTIVITY, mapped.type());
        assertEquals("模型请求执行受控工具", mapped.text());
    }

    @Test
    void mapsInteractionAndTerminalAnswer() {
        assertEquals(
                AgentExecutionEventType.INPUT_REQUIRED,
                mapper.map(new OutputSchema("__interaction__", 0, "raw-sdk-state")).type());
        assertEquals(
                AgentExecutionEventType.COMPLETED,
                mapper.map(new OutputSchema("answer", 0, Map.of("output", "final"))).type());
    }
}
