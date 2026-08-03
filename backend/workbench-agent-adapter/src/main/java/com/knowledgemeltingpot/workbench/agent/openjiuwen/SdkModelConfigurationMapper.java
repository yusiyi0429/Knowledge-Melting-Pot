package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration;
import com.knowledgemeltingpot.workbench.agent.ModelProvider;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;

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

    static String sdkProvider(ModelProvider provider) {
        return switch (provider) {
            case OPENAI_COMPATIBLE -> "OpenAI";
            case DASHSCOPE -> "DashScope";
        };
    }
}
