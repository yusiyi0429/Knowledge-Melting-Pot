package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class ReactSdkJobExecutor implements SdkJobExecutor {
    private final AgentExecutionRequest request;
    private final ReActAgent agent;
    private final AgentSessionApi session;
    private final AtomicReference<Iterator<?>> activeStream = new AtomicReference<>();

    ReactSdkJobExecutor(
            AgentModelConfiguration modelConfiguration,
            AgentExecutionRequest request,
            String jobId,
            String sessionId) {
        this.request = request;
        AgentCard card = AgentCard.builder()
                .id("react-" + jobId)
                .name("knowledge-extraction-react")
                .description("Controlled knowledge extraction ReAct agent")
                .build();

        ReActAgentConfig agentConfiguration = ReActAgentConfig.builder()
                .modelName(modelConfiguration.modelName())
                .modelProvider(SdkModelConfigurationMapper.sdkProvider(modelConfiguration.provider()))
                .apiKey(modelConfiguration.apiKey())
                .apiBase(modelConfiguration.apiBase().toString())
                .modelClientConfig(SdkModelConfigurationMapper.clientConfiguration(modelConfiguration, jobId))
                .modelConfigObj(SdkModelConfigurationMapper.requestConfiguration(modelConfiguration))
                .promptTemplate(List.of(Map.of(
                        "role", "system",
                        "content", modelConfiguration.systemPrompt())))
                .maxIterations(modelConfiguration.maxIterations())
                .build();

        this.agent = new ReActAgent(card);
        this.agent.configure(agentConfiguration);
        this.session = new AgentSessionApi(
                sessionId,
                Map.of(
                        "job_id", jobId,
                        "workspace_id", request.workspaceId(),
                        "actor_id", request.actorId()),
                card,
                List.of(StreamMode.OUTPUT));
    }

    @Override
    public SdkTerminalResult execute() {
        Object rawResult = agent.invoke(inputs(), session);
        return SdkResultMapper.fromReact(rawResult);
    }

    @Override
    public Iterator<?> stream() {
        Iterator<?> iterator = agent.stream(inputs(), session, List.of(StreamMode.OUTPUT));
        activeStream.set(iterator);
        return iterator;
    }

    @Override
    public void cancel() {
        closeStream(activeStream.getAndSet(null));
    }

    @Override
    public void close() {
        cancel();
    }

    private Map<String, Object> inputs() {
        return Map.of(
                "query", request.prompt(),
                "conversation_id", session.getSessionId());
    }

    private static void closeStream(Iterator<?> iterator) {
        if (iterator instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Closing is best effort; the lifecycle also interrupts the consuming thread.
            }
        }
    }
}
