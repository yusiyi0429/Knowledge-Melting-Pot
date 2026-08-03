package com.knowledgemeltingpot.workbench.agent;

/** Lifecycle state of one isolated job runtime. */
public enum AgentRuntimeState {
    NEW,
    RUNNING,
    COMPLETED,
    INPUT_REQUIRED,
    FAILED,
    CANCELLED,
    CLOSED
}
