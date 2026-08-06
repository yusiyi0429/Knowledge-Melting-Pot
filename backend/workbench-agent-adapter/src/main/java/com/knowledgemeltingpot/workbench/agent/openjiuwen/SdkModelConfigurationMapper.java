package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration;
import com.knowledgemeltingpot.workbench.agent.ModelProvider;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.session.WorkflowSessionApi;

import java.util.Map;

final class SdkModelConfigurationMapper {
    private SdkModelConfigurationMapper() {
    }

    static ModelClientConfig clientConfiguration(AgentModelConfiguration configuration, String clientId) {
        return ModelClientConfig.builder()
                .clientId(clientId)
                .clientProvider(sdkProvider(configuration.provider()))
                .apiKey(configuration.apiKey())
                .apiBase(configuration.apiBase().toString())
                .timeout(configuration.timeout().toMillis() / 1_000.0)
                .maxRetries(configuration.maxRetries())
                .verifySsl(configuration.verifySsl())
                .build();
    }

    static ModelRequestConfig requestConfiguration(AgentModelConfiguration configuration) {
        return ModelRequestConfig.builder()
                .modelName(configuration.modelName())
                .temperature(configuration.temperature())
                .maxTokens(configuration.maxTokens())
                .build();
    }

    static WorkflowSessionApi workflowSession(AgentModelConfiguration configuration, String sessionId) {
        double timeoutSeconds = configuration.timeout().toMillis() / 1_000.0;
        return new WorkflowSessionApi(null, sessionId, Map.of("_execute_timeout", timeoutSeconds));
    }

    static String sdkProvider(ModelProvider provider) {
        return switch (provider) {
            case OPENAI_COMPATIBLE -> "OpenAI";
            case DASHSCOPE -> "DashScope";
        };
    }
}
