package com.knowledgemeltingpot.workbench.agent;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Immutable model configuration with a secret-safe {@link #toString()}. */
public final class AgentModelConfiguration {
    private final ModelProvider provider;
    private final String modelName;
    private final URI apiBase;
    private final String apiKey;
    private final Duration timeout;
    private final int maxRetries;
    private final double temperature;
    private final int maxTokens;
    private final int maxIterations;
    private final int maxPromptCharacters;
    private final boolean verifySsl;
    private final String systemPrompt;

    private AgentModelConfiguration(Builder builder) {
        this.provider = Objects.requireNonNull(builder.provider, "provider must not be null");
        this.modelName = requireText(builder.modelName, "modelName");
        this.apiBase = Objects.requireNonNull(builder.apiBase, "apiBase must not be null");
        this.apiKey = requireText(builder.apiKey, "apiKey");
        this.timeout = Objects.requireNonNull(builder.timeout, "timeout must not be null");
        this.systemPrompt = requireText(builder.systemPrompt, "systemPrompt");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (builder.maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        if (builder.temperature < 0 || builder.temperature > 2) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (builder.maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (builder.maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        if (builder.maxPromptCharacters <= 0) {
            throw new IllegalArgumentException("maxPromptCharacters must be positive");
        }
        this.maxRetries = builder.maxRetries;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.maxIterations = builder.maxIterations;
        this.maxPromptCharacters = builder.maxPromptCharacters;
        this.verifySsl = builder.verifySsl;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ModelProvider provider() {
        return provider;
    }

    public String modelName() {
        return modelName;
    }

    public URI apiBase() {
        return apiBase;
    }

    public String apiKey() {
        return apiKey;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public double temperature() {
        return temperature;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int maxIterations() {
        return maxIterations;
    }

    public int maxPromptCharacters() {
        return maxPromptCharacters;
    }

    public boolean verifySsl() {
        return verifySsl;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    /** Never prints the API key or prompt content. */
    @Override
    public String toString() {
        return "AgentModelConfiguration[provider=" + provider
                + ", modelName=" + modelName
                + ", apiBase=" + apiBase
                + ", apiKey=[REDACTED]"
                + ", timeout=" + timeout
                + ", maxRetries=" + maxRetries
                + ", temperature=" + temperature
                + ", maxTokens=" + maxTokens
                + ", maxIterations=" + maxIterations
                + ", maxPromptCharacters=" + maxPromptCharacters
                + ", verifySsl=" + verifySsl
                + ", systemPromptLength=" + systemPrompt.length() + "]";
    }

    public static final class Builder {
        private ModelProvider provider = ModelProvider.OPENAI_COMPATIBLE;
        private String modelName;
        private URI apiBase;
        private String apiKey;
        private Duration timeout = Duration.ofSeconds(60);
        private int maxRetries = 2;
        private double temperature = 0.2;
        private int maxTokens = 4096;
        private int maxIterations = 5;
        private int maxPromptCharacters = 200_000;
        private boolean verifySsl = true;
        private String systemPrompt = "你是知识萃取助手。只基于提供的材料提炼事实、关系和可复用知识；不确定时明确标注。";

        private Builder() {
        }

        public Builder provider(ModelProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder apiBase(URI apiBase) {
            this.apiBase = apiBase;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder maxPromptCharacters(int maxPromptCharacters) {
            this.maxPromptCharacters = maxPromptCharacters;
            return this;
        }

        public Builder verifySsl(boolean verifySsl) {
            this.verifySsl = verifySsl;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public AgentModelConfiguration build() {
            return new AgentModelConfiguration(this);
        }
    }
}
