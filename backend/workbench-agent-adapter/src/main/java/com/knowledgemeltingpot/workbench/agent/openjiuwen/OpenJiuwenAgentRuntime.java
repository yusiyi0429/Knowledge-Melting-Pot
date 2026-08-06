package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionEvent;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionEventType;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.agent.AgentRuntimeLifecycle;
import com.knowledgemeltingpot.workbench.agent.AgentRuntimeState;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class OpenJiuwenAgentRuntime implements AgentRuntimeLifecycle {
    private static final System.Logger LOGGER = System.getLogger(OpenJiuwenAgentRuntime.class.getName());

    private final String jobId;
    private final String sessionId;
    private final AgentExecutionRequest request;
    private final SdkJobExecutor executor;
    private final Clock clock;
    private final SdkStreamEventMapper streamEventMapper;
    private final AtomicReference<AgentRuntimeState> state = new AtomicReference<>(AgentRuntimeState.NEW);
    private final AtomicReference<Thread> activeThread = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();

    OpenJiuwenAgentRuntime(
            String jobId,
            String sessionId,
            AgentExecutionRequest request,
            SdkJobExecutor executor) {
        this(jobId, sessionId, request, executor, Clock.systemUTC(), new SdkStreamEventMapper());
    }

    OpenJiuwenAgentRuntime(
            String jobId,
            String sessionId,
            AgentExecutionRequest request,
            SdkJobExecutor executor,
            Clock clock,
            SdkStreamEventMapper streamEventMapper) {
        this.jobId = requireText(jobId, "jobId");
        this.sessionId = requireText(sessionId, "sessionId");
        this.request = Objects.requireNonNull(request, "request must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.streamEventMapper = Objects.requireNonNull(streamEventMapper, "streamEventMapper must not be null");
    }

    @Override
    public String jobId() {
        return jobId;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public AgentRuntimeState state() {
        return state.get();
    }

    @Override
    public AgentExecutionResult execute() {
        Instant startedAt = begin();
        try {
            SdkTerminalResult terminal = executor.execute();
            if (cancelled.get()) {
                return cancelledResult(startedAt);
            }
            return complete(terminal, startedAt);
        } catch (RuntimeException exception) {
            return fail(exception, startedAt);
        } finally {
            activeThread.compareAndSet(Thread.currentThread(), null);
        }
    }

    @Override
    public AgentExecutionResult stream(Consumer<AgentExecutionEvent> eventConsumer) {
        Objects.requireNonNull(eventConsumer, "eventConsumer must not be null");
        Instant startedAt = begin();
        StringBuilder text = new StringBuilder();
        MappedSdkEvent terminalEvent = null;
        try {
            emit(eventConsumer, AgentExecutionEventType.STARTED, "", "started");
            Iterator<?> iterator = executor.stream();
            while (!cancelled.get() && iterator.hasNext()) {
                MappedSdkEvent mapped = streamEventMapper.map(iterator.next());
                if (mapped.type() == AgentExecutionEventType.TEXT_DELTA && !mapped.text().isEmpty()) {
                    text.append(mapped.text());
                }
                if (isTerminal(mapped.type())) {
                    terminalEvent = mapped;
                }
                emit(eventConsumer, mapped.type(), mapped.text(), mapped.code());
            }

            if (cancelled.get()) {
                emitBestEffort(eventConsumer, AgentExecutionEventType.CANCELLED, "", "cancelled");
                return cancelledResult(startedAt);
            }

            if (terminalEvent == null) {
                String output = text.toString();
                emit(eventConsumer, AgentExecutionEventType.COMPLETED, output, "completed");
                return complete(SdkTerminalResult.completed(output), startedAt);
            }
            return complete(fromStreamTerminal(terminalEvent, text.toString()), startedAt);
        } catch (RuntimeException exception) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                emitBestEffort(eventConsumer, AgentExecutionEventType.CANCELLED, "", "cancelled");
                return cancelledResult(startedAt);
            }
            AgentExecutionResult failed = fail(exception, startedAt);
            emitBestEffort(
                    eventConsumer,
                    AgentExecutionEventType.FAILED,
                    failed.failureMessage(),
                    failed.failureCode());
            return failed;
        } finally {
            cancelExecutorBestEffort();
            activeThread.compareAndSet(Thread.currentThread(), null);
        }
    }

    @Override
    public void cancel() {
        AgentRuntimeState current = state.get();
        if (current == AgentRuntimeState.COMPLETED
                || current == AgentRuntimeState.INPUT_REQUIRED
                || current == AgentRuntimeState.FAILED
                || current == AgentRuntimeState.CLOSED) {
            return;
        }
        cancelled.set(true);
        state.updateAndGet(value -> value == AgentRuntimeState.CLOSED ? value : AgentRuntimeState.CANCELLED);
        cancelExecutorBestEffort();
        Thread thread = activeThread.get();
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void close() {
        if (state.get() == AgentRuntimeState.RUNNING) {
            cancel();
        }
        try {
            executor.close();
        } finally {
            state.set(AgentRuntimeState.CLOSED);
        }
    }

    private Instant begin() {
        if (!state.compareAndSet(AgentRuntimeState.NEW, AgentRuntimeState.RUNNING)) {
            throw new IllegalStateException("Agent runtime is one-shot; current state=" + state.get());
        }
        activeThread.set(Thread.currentThread());
        Instant startedAt = clock.instant();
        LOGGER.log(
                System.Logger.Level.INFO,
                "Agent job {0} started with mode {1}",
                jobId,
                request.executionMode());
        return startedAt;
    }

    private AgentExecutionResult complete(SdkTerminalResult terminal, Instant startedAt) {
        Instant completedAt = notBefore(clock.instant(), startedAt);
        AgentRuntimeState terminalState = switch (terminal.status()) {
            case COMPLETED -> AgentRuntimeState.COMPLETED;
            case INPUT_REQUIRED -> AgentRuntimeState.INPUT_REQUIRED;
            case FAILED -> AgentRuntimeState.FAILED;
            case CANCELLED -> AgentRuntimeState.CANCELLED;
        };
        state.set(terminalState);
        return new AgentExecutionResult(
                jobId,
                sessionId,
                terminal.status(),
                terminal.output(),
                terminal.failureCode(),
                terminal.failureMessage(),
                startedAt,
                completedAt);
    }

    private AgentExecutionResult cancelledResult(Instant startedAt) {
        state.set(AgentRuntimeState.CANCELLED);
        Instant completedAt = notBefore(clock.instant(), startedAt);
        return new AgentExecutionResult(
                jobId,
                sessionId,
                AgentExecutionStatus.CANCELLED,
                "",
                "CANCELLED",
                "Agent job was cancelled",
                startedAt,
                completedAt);
    }

    private AgentExecutionResult fail(RuntimeException exception, Instant startedAt) {
        state.set(AgentRuntimeState.FAILED);
        LOGGER.log(
                System.Logger.Level.ERROR,
                "Agent job {0} failed with {1}",
                jobId,
                exception.getClass().getSimpleName());
        Instant completedAt = notBefore(clock.instant(), startedAt);
        return new AgentExecutionResult(
                jobId,
                sessionId,
                AgentExecutionStatus.FAILED,
                "",
                "AGENT_RUNTIME_FAILURE",
                "Agent execution failed",
                startedAt,
                completedAt);
    }

    private SdkTerminalResult fromStreamTerminal(MappedSdkEvent terminal, String collectedText) {
        String terminalText = "workflow_end".equals(terminal.code()) && !collectedText.isBlank()
                ? collectedText
                : terminal.text().isBlank() ? collectedText : terminal.text();
        return switch (terminal.type()) {
            case COMPLETED -> SdkTerminalResult.completed(terminalText);
            case INPUT_REQUIRED -> SdkTerminalResult.inputRequired(terminalText);
            case FAILED -> SdkTerminalResult.failed(
                    terminal.code().isBlank() ? "SDK_STREAM_FAILURE" : terminal.code(),
                    SensitiveTextRedactor.redact(terminalText));
            case CANCELLED -> new SdkTerminalResult(
                    AgentExecutionStatus.CANCELLED,
                    "",
                    "CANCELLED",
                    "Agent job was cancelled");
            default -> SdkTerminalResult.completed(terminalText);
        };
    }

    private void emit(
            Consumer<AgentExecutionEvent> consumer,
            AgentExecutionEventType type,
            String text,
            String code) {
        consumer.accept(new AgentExecutionEvent(
                jobId,
                sessionId,
                sequence.getAndIncrement(),
                type,
                text,
                code,
                clock.instant()));
    }

    private void emitBestEffort(
            Consumer<AgentExecutionEvent> consumer,
            AgentExecutionEventType type,
            String text,
            String code) {
        try {
            emit(consumer, type, text, code);
        } catch (RuntimeException ignored) {
            // A disconnected downstream consumer must not prevent SDK cleanup.
        }
    }

    private static boolean isTerminal(AgentExecutionEventType type) {
        return type == AgentExecutionEventType.COMPLETED
                || type == AgentExecutionEventType.INPUT_REQUIRED
                || type == AgentExecutionEventType.FAILED
                || type == AgentExecutionEventType.CANCELLED;
    }

    private static Instant notBefore(Instant value, Instant floor) {
        return value.isBefore(floor) ? floor : value;
    }

    private void cancelExecutorBestEffort() {
        try {
            executor.cancel();
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Agent job {0} SDK cancellation failed with {1}",
                    jobId,
                    exception.getClass().getSimpleName());
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
