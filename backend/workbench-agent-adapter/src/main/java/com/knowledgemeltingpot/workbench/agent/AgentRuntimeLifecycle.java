package com.knowledgemeltingpot.workbench.agent;

import java.util.function.Consumer;

/** One-shot, per-job runtime. Implementations must never be shared between jobs. */
public interface AgentRuntimeLifecycle extends AutoCloseable {
    String jobId();

    String sessionId();

    AgentRuntimeState state();

    AgentExecutionResult execute();

    AgentExecutionResult stream(Consumer<AgentExecutionEvent> eventConsumer);

    void cancel();

    @Override
    void close();
}
