package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration;
import com.knowledgemeltingpot.workbench.agent.ModelProvider;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SdkModelConfigurationMapperTest {
    @Test
    void mapsOnlyTheTwoControlledProviderNames() {
        assertEquals("OpenAI", SdkModelConfigurationMapper.sdkProvider(ModelProvider.OPENAI_COMPATIBLE));
        assertEquals("DashScope", SdkModelConfigurationMapper.sdkProvider(ModelProvider.DASHSCOPE));
    }

    @Test
    void buildsPinnedSdkConfigurationWithoutChangingSecret() {
        AgentModelConfiguration configuration = AgentModelConfiguration.builder()
                .provider(ModelProvider.OPENAI_COMPATIBLE)
                .modelName("test-model")
                .apiBase(URI.create("https://example.invalid/v1"))
                .apiKey("test-secret")
                .timeout(Duration.ofMinutes(3))
                .build();

        ModelClientConfig sdk = SdkModelConfigurationMapper.clientConfiguration(configuration, "job-test");

        assertEquals("OpenAI", sdk.getClientProvider());
        assertEquals("test-secret", sdk.getApiKey());
        assertEquals("job-test", sdk.getClientId());
        assertEquals(180.0, sdk.getTimeout());
        assertEquals(180.0, SdkModelConfigurationMapper.workflowSession(configuration, "session-test")
                .getEnvs().get("_execute_timeout"));
    }
}
