package com.knowledgemeltingpot.workbench.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultKnowledgeExtractionAdapterTest {
    @Test
    void closesPerJobRuntimeAfterExecution() {
        AtomicBoolean closed = new AtomicBoolean();
        AgentRuntimeFactory factory = request -> new FakeRuntime(closed);
        DefaultKnowledgeExtractionAdapter adapter = new DefaultKnowledgeExtractionAdapter(factory);

        AgentExecutionResult result = adapter.execute(new AgentExecutionRequest(
                "workspace", "actor", "extract this", AgentExecutionMode.REACT));

        assertEquals(AgentExecutionStatus.COMPLETED, result.status());
        assertTrue(closed.get());
    }

    private static final class FakeRuntime implements AgentRuntimeLifecycle {
        private final AtomicBoolean closed;

        private FakeRuntime(AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public String jobId() {
            return "job-test";
        }

        @Override
        public String sessionId() {
            return "session-test";
        }

        @Override
        public AgentRuntimeState state() {
            return closed.get() ? AgentRuntimeState.CLOSED : AgentRuntimeState.NEW;
        }

        @Override
        public AgentExecutionResult execute() {
            return new AgentExecutionResult(
                    jobId(),
                    sessionId(),
                    AgentExecutionStatus.COMPLETED,
                    "ok",
                    "",
                    "",
                    Instant.EPOCH,
                    Instant.EPOCH);
        }

        @Override
        public AgentExecutionResult stream(Consumer<AgentExecutionEvent> eventConsumer) {
            return execute();
        }

        @Override
        public void cancel() {
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
