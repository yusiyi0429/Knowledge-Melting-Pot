package com.knowledgemeltingpot.workbench.agent;

/** Stable event vocabulary used by workers and HTTP/SSE adapters. */
public enum AgentExecutionEventType {
    STARTED,
    TEXT_DELTA,
    TOOL_ACTIVITY,
    PROGRESS,
    INPUT_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELLED
}
