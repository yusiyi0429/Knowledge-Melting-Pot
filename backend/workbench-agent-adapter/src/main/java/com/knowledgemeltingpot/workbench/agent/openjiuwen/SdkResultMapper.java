package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.openjiuwen.core.workflow.WorkflowExecutionState;
import com.openjiuwen.core.workflow.WorkflowOutput;

import java.util.Map;

final class SdkResultMapper {
    private SdkResultMapper() {
    }

    static SdkTerminalResult fromReact(Object rawResult) {
        if (!(rawResult instanceof Map<?, ?> result)) {
            return SdkTerminalResult.failed("SDK_CONTRACT_ERROR", "Unexpected ReAct result shape");
        }
        Object rawResultType = result.containsKey("result_type") ? result.get("result_type") : "error";
        String resultType = String.valueOf(rawResultType);
        String output = SdkValueRenderer.render(result.get("output"));
        return switch (resultType) {
            case "answer" -> SdkTerminalResult.completed(output);
            case "interrupt" -> SdkTerminalResult.inputRequired("需要补充输入后继续执行");
            default -> SdkTerminalResult.failed(
                    "AGENT_EXECUTION_FAILED",
                    SensitiveTextRedactor.redact(output.isBlank() ? "Agent execution failed" : output));
        };
    }

    static SdkTerminalResult fromWorkflow(WorkflowOutput result) {
        if (result == null || result.getState() == null) {
            return SdkTerminalResult.failed("SDK_CONTRACT_ERROR", "Workflow returned no state");
        }
        String output = SdkValueRenderer.render(result.getResult());
        if (result.getState() == WorkflowExecutionState.COMPLETED) {
            return SdkTerminalResult.completed(output);
        }
        if (result.getState() == WorkflowExecutionState.INPUT_REQUIRED) {
            return SdkTerminalResult.inputRequired(output);
        }
        return SdkTerminalResult.failed(
                "WORKFLOW_EXECUTION_FAILED",
                SensitiveTextRedactor.redact(output.isBlank() ? "Workflow execution failed" : output));
    }
}
