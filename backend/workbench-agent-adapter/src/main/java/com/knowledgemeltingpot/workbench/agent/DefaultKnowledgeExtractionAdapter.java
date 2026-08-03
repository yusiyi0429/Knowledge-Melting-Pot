package com.knowledgemeltingpot.workbench.agent;

import java.util.Objects;
import java.util.function.Consumer;

/** Default port implementation that scopes one runtime to exactly one call. */
public final class DefaultKnowledgeExtractionAdapter implements KnowledgeExtractionPort {
    private final AgentRuntimeFactory runtimeFactory;

    public DefaultKnowledgeExtractionAdapter(AgentRuntimeFactory runtimeFactory) {
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory must not be null");
    }

    @Override
    public AgentExecutionResult execute(AgentExecutionRequest request) {
        try (AgentRuntimeLifecycle runtime = runtimeFactory.create(request)) {
            return runtime.execute();
        }
    }

    @Override
    public AgentExecutionResult stream(
            AgentExecutionRequest request,
            Consumer<AgentExecutionEvent> eventConsumer) {
        Objects.requireNonNull(eventConsumer, "eventConsumer must not be null");
        try (AgentRuntimeLifecycle runtime = runtimeFactory.create(request)) {
            return runtime.stream(eventConsumer);
        }
    }
}
