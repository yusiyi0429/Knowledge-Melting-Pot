package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration;
import com.knowledgemeltingpot.workbench.agent.AgentRuntimeLifecycle;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenJiuwenAgentRuntimeFactoryTest {
    @Test
    void createsFreshRuntimeExecutorAndServerIdsForEveryJob() {
        AtomicInteger jobIds = new AtomicInteger();
        AtomicInteger sessionIds = new AtomicInteger();
        AtomicInteger executors = new AtomicInteger();
        OpenJiuwenAgentRuntimeFactory factory = new OpenJiuwenAgentRuntimeFactory(
                modelConfiguration(1_000),
                () -> "job-" + jobIds.incrementAndGet(),
                () -> "session-" + sessionIds.incrementAndGet(),
                (request, jobId, sessionId) -> {
                    executors.incrementAndGet();
                    return new StubExecutor();
                });
        AgentExecutionRequest request = new AgentExecutionRequest(
                "workspace", "actor", "extract", AgentExecutionMode.REACT);

        try (AgentRuntimeLifecycle first = factory.create(request);
                AgentRuntimeLifecycle second = factory.create(request)) {
            assertNotSame(first, second);
            assertEquals("job-1", first.jobId());
            assertEquals("job-2", second.jobId());
            assertEquals("session-1", first.sessionId());
            assertEquals("session-2", second.sessionId());
            assertEquals(2, executors.get());
        }
    }

    @Test
    void rejectsPromptsBeyondServerPolicyBeforeCreatingSdkRuntime() {
        OpenJiuwenAgentRuntimeFactory factory = new OpenJiuwenAgentRuntimeFactory(
                modelConfiguration(4),
                () -> "job-1",
                () -> "session-1",
                (request, jobId, sessionId) -> new StubExecutor());
        AgentExecutionRequest request = new AgentExecutionRequest(
                "workspace", "actor", "12345", AgentExecutionMode.WORKFLOW);

        assertThrows(IllegalArgumentException.class, () -> factory.create(request));
    }

    private static AgentModelConfiguration modelConfiguration(int maxPromptCharacters) {
        return AgentModelConfiguration.builder()
                .modelName("test-model")
                .apiBase(URI.create("https://example.invalid/v1"))
                .apiKey("test-only-key")
                .maxPromptCharacters(maxPromptCharacters)
                .build();
    }

    private static final class StubExecutor implements SdkJobExecutor {
        @Override
        public SdkTerminalResult execute() {
            return SdkTerminalResult.completed("ok");
        }

        @Override
        public java.util.Iterator<?> stream() {
            return Collections.emptyIterator();
        }
    }
}
