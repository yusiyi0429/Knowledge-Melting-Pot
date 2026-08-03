package com.knowledgemeltingpot.workbench.agent;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModelConfigurationTest {
    @Test
    void toStringRedactsApiKeyAndSystemPrompt() {
        AgentModelConfiguration configuration = AgentModelConfiguration.builder()
                .provider(ModelProvider.DASHSCOPE)
                .modelName("qwen-test")
                .apiBase(URI.create("https://example.invalid/v1"))
                .apiKey("sk-super-secret")
                .systemPrompt("confidential system instructions")
                .build();

        String rendered = configuration.toString();
        assertTrue(rendered.contains("apiKey=[REDACTED]"));
        assertFalse(rendered.contains("sk-super-secret"));
        assertFalse(rendered.contains("confidential system instructions"));
    }

    @Test
    void rejectsUnsafeExecutionLimits() {
        AgentModelConfiguration.Builder builder = AgentModelConfiguration.builder()
                .modelName("test-model")
                .apiBase(URI.create("https://example.invalid/v1"))
                .apiKey("test-key")
                .maxIterations(0);

        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
