package com.knowledgemeltingpot.workbench.agent;

/** Terminal result of an agent job. */
public enum AgentExecutionStatus {
    COMPLETED,
    INPUT_REQUIRED,
    FAILED,
    CANCELLED
}
