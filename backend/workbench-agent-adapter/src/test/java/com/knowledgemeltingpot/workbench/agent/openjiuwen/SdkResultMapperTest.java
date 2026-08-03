package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.openjiuwen.core.workflow.WorkflowExecutionState;
import com.openjiuwen.core.workflow.WorkflowOutput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SdkResultMapperTest {
    @Test
    void convertsReactDynamicMapToTypedResult() {
        SdkTerminalResult result = SdkResultMapper.fromReact(Map.of(
                "result_type", "answer",
                "output", "typed output"));

        assertEquals(AgentExecutionStatus.COMPLETED, result.status());
        assertEquals("typed output", result.output());
    }

    @Test
    void convertsWorkflowStateAndNestedOutputToTypedResult() {
        WorkflowOutput output = new WorkflowOutput(
                Map.of("output", Map.of("answer", "workflow output")),
                WorkflowExecutionState.COMPLETED);

        SdkTerminalResult result = SdkResultMapper.fromWorkflow(output);

        assertEquals(AgentExecutionStatus.COMPLETED, result.status());
        assertEquals("workflow output", result.output());
    }

    @Test
    void redactsSdkFailureMessages() {
        SdkTerminalResult result = SdkResultMapper.fromReact(Map.of(
                "result_type", "error",
                "output", "Authorization: Bearer top-secret"));

        assertEquals(AgentExecutionStatus.FAILED, result.status());
        assertFalse(result.failureMessage().contains("top-secret"));
    }
}
