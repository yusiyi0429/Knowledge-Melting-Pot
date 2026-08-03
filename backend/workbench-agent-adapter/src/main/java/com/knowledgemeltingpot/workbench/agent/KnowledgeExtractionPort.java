package com.knowledgemeltingpot.workbench.agent;

import java.util.function.Consumer;

/** Business-facing port consumed by job workers or transport adapters. */
public interface KnowledgeExtractionPort {
    AgentExecutionResult execute(AgentExecutionRequest request);

    AgentExecutionResult stream(
            AgentExecutionRequest request,
            Consumer<AgentExecutionEvent> eventConsumer);
}
