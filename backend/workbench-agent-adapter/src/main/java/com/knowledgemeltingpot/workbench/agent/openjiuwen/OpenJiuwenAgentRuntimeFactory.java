package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration;
import com.knowledgemeltingpot.workbench.agent.AgentRuntimeFactory;
import com.knowledgemeltingpot.workbench.agent.AgentRuntimeLifecycle;

import java.util.Objects;
import java.util.function.Supplier;

/** Production factory for the pinned openJiuwen 0.1.13 adapter. */
public final class OpenJiuwenAgentRuntimeFactory implements AgentRuntimeFactory {
    private final AgentModelConfiguration modelConfiguration;
    private final Supplier<String> jobIdGenerator;
    private final Supplier<String> sessionIdGenerator;
    private final SdkJobExecutorFactory executorFactory;

    public OpenJiuwenAgentRuntimeFactory(AgentModelConfiguration modelConfiguration) {
        this(
                modelConfiguration,
                ServerIdGenerator.prefixed("job_"),
                ServerIdGenerator.prefixed("session_"),
                null);
    }

    OpenJiuwenAgentRuntimeFactory(
            AgentModelConfiguration modelConfiguration,
            Supplier<String> jobIdGenerator,
            Supplier<String> sessionIdGenerator,
            SdkJobExecutorFactory executorFactory) {
        this.modelConfiguration = Objects.requireNonNull(
                modelConfiguration,
                "modelConfiguration must not be null");
        this.jobIdGenerator = Objects.requireNonNull(jobIdGenerator, "jobIdGenerator must not be null");
        this.sessionIdGenerator = Objects.requireNonNull(
                sessionIdGenerator,
                "sessionIdGenerator must not be null");
        this.executorFactory = executorFactory != null ? executorFactory : this::createSdkExecutor;
        AgentCoreDependencyProbe.verify();
        AgentCoreLoggingGuard.install();
    }

    @Override
    public AgentRuntimeLifecycle create(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.prompt().length() > modelConfiguration.maxPromptCharacters()) {
            throw new IllegalArgumentException(
                    "prompt exceeds maxPromptCharacters=" + modelConfiguration.maxPromptCharacters());
        }
        String jobId = requireGeneratedId(jobIdGenerator.get(), "jobId");
        String sessionId = requireGeneratedId(sessionIdGenerator.get(), "sessionId");
        SdkJobExecutor executor = executorFactory.create(request, jobId, sessionId);
        if (executor == null) {
            throw new IllegalStateException("SDK executor factory returned null");
        }
        return new OpenJiuwenAgentRuntime(jobId, sessionId, request, executor);
    }

    private SdkJobExecutor createSdkExecutor(
            AgentExecutionRequest request,
            String jobId,
            String sessionId) {
        return switch (request.executionMode()) {
            case REACT -> new ReactSdkJobExecutor(modelConfiguration, request, jobId, sessionId);
            case WORKFLOW -> new WorkflowSdkJobExecutor(modelConfiguration, request, jobId, sessionId);
        };
    }

    private static String requireGeneratedId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " generator returned a blank value");
        }
        return value;
    }
}
