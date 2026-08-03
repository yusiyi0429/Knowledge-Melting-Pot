package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;

record SdkTerminalResult(
        AgentExecutionStatus status,
        String output,
        String failureCode,
        String failureMessage) {

    SdkTerminalResult {
        output = output == null ? "" : output;
        failureCode = failureCode == null ? "" : failureCode;
        failureMessage = failureMessage == null ? "" : failureMessage;
    }

    static SdkTerminalResult completed(String output) {
        return new SdkTerminalResult(AgentExecutionStatus.COMPLETED, output, "", "");
    }

    static SdkTerminalResult inputRequired(String output) {
        return new SdkTerminalResult(AgentExecutionStatus.INPUT_REQUIRED, output, "", "");
    }

    static SdkTerminalResult failed(String code, String message) {
        return new SdkTerminalResult(AgentExecutionStatus.FAILED, "", code, message);
    }
}
