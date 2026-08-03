package com.knowledgemeltingpot.workbench.agent;

/** Creates a fresh isolated runtime and server-side identifiers for every job. */
@FunctionalInterface
public interface AgentRuntimeFactory {
    AgentRuntimeLifecycle create(AgentExecutionRequest request);
}
