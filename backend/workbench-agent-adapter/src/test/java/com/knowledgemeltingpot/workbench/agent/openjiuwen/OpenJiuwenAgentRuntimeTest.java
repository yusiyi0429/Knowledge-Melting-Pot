package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionEvent;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionEventType;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.agent.AgentRuntimeState;
import com.openjiuwen.core.session.stream.OutputSchema;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenJiuwenAgentRuntimeTest {
    private static final AgentExecutionRequest REQUEST = new AgentExecutionRequest(
            "workspace", "actor", "source material", AgentExecutionMode.REACT);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void convertsSdkTerminalResultAndEnforcesOneShotLifecycle() {
        StubExecutor executor = new StubExecutor(SdkTerminalResult.completed("typed result"), List.of());
        OpenJiuwenAgentRuntime runtime = runtime(executor);

        AgentExecutionResult result = runtime.execute();

        assertEquals(AgentExecutionStatus.COMPLETED, result.status());
        assertEquals("typed result", result.output());
        assertEquals(AgentRuntimeState.COMPLETED, runtime.state());
        assertThrows(IllegalStateException.class, runtime::execute);
    }

    @Test
    void convertsBlockingSdkStreamToOrderedBusinessEvents() {
        StubExecutor executor = new StubExecutor(
                SdkTerminalResult.completed("unused"),
                List.of(
                        new OutputSchema("llm_output", 0, Map.of("content", "delta")),
                        new OutputSchema("answer", 0, Map.of("output", "final answer"))));
        OpenJiuwenAgentRuntime runtime = runtime(executor);
        List<AgentExecutionEvent> events = new ArrayList<>();

        AgentExecutionResult result = runtime.stream(events::add);

        assertEquals(AgentExecutionStatus.COMPLETED, result.status());
        assertEquals("final answer", result.output());
        assertEquals(
                List.of(
                        AgentExecutionEventType.STARTED,
                        AgentExecutionEventType.TEXT_DELTA,
                        AgentExecutionEventType.COMPLETED),
                events.stream().map(AgentExecutionEvent::type).toList());
        assertEquals(List.of(0L, 1L, 2L), events.stream().map(AgentExecutionEvent::sequence).toList());
        assertTrue(executor.cancelled.get(), "stream cleanup must invoke the SDK cancellation hook");
    }

    @Test
    void workflowEndMarkerCannotOverwriteCollectedModelOutput() {
        StubExecutor executor = new StubExecutor(
                SdkTerminalResult.completed("unused"),
                List.of(
                        new OutputSchema("llm_output", 0, Map.of("content", "{\"rules\":[]")),
                        new OutputSchema("llm_output", 1, Map.of("content", ",\"flows\":[]}")),
                        new OutputSchema("end node stream", 2, "end-node-complete")));

        AgentExecutionResult result = runtime(executor).stream(ignored -> { });

        assertEquals(AgentExecutionStatus.COMPLETED, result.status());
        assertEquals("{\"rules\":[],\"flows\":[]}", result.output());
    }

    @Test
    void replacesRuntimeFailureDetailSoModelOutputAndCredentialsCannotLeak() {
        SdkJobExecutor executor = new StubExecutor(SdkTerminalResult.completed("unused"), List.of()) {
            @Override
            public SdkTerminalResult execute() {
                throw new IllegalStateException("raw-model-output Authorization: Bearer secret-token");
            }
        };

        AgentExecutionResult result = runtime(executor).execute();

        assertEquals(AgentExecutionStatus.FAILED, result.status());
        assertFalse(result.failureMessage().contains("secret-token"));
        assertFalse(result.failureMessage().contains("raw-model-output"));
        assertEquals("Agent execution failed", result.failureMessage());
    }

    private static OpenJiuwenAgentRuntime runtime(SdkJobExecutor executor) {
        return new OpenJiuwenAgentRuntime(
                "job-test",
                "session-test",
                REQUEST,
                executor,
                CLOCK,
                new SdkStreamEventMapper());
    }

    private static class StubExecutor implements SdkJobExecutor {
        private final SdkTerminalResult terminalResult;
        private final List<?> streamItems;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private StubExecutor(SdkTerminalResult terminalResult, List<?> streamItems) {
            this.terminalResult = terminalResult;
            this.streamItems = streamItems;
        }

        @Override
        public SdkTerminalResult execute() {
            return terminalResult;
        }

        @Override
        public Iterator<?> stream() {
            return streamItems.iterator();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }
}
