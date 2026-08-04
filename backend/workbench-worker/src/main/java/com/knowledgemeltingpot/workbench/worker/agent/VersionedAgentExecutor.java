package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration;
import com.knowledgemeltingpot.workbench.agent.DefaultKnowledgeExtractionAdapter;
import com.knowledgemeltingpot.workbench.agent.ModelProvider;
import com.knowledgemeltingpot.workbench.agent.openjiuwen.OpenJiuwenAgentRuntimeFactory;
import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

/** Resolves immutable model and Skill versions for exactly one Agent call. */
public final class VersionedAgentExecutor {
    private static final String BASE_SYSTEM_PROMPT = """
            你是知识萃取工作台中的受控智能体。只能使用调用中提供的可信上下文；
            不得读取或泄露凭据，不得执行脚本，不得输出模型私有推理过程。
            """;

    private final ModelConnectionRepository modelRepository;
    private final SkillRepository skillRepository;
    private final CredentialCipher credentialCipher;
    private final ModelEndpointPolicy endpointPolicy;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final AgentCall agentCall;

    public VersionedAgentExecutor(ModelConnectionRepository modelRepository, SkillRepository skillRepository,
            CredentialCipher credentialCipher, ModelEndpointPolicy endpointPolicy, ObjectMapper objectMapper,
            Duration timeout) {
        this(modelRepository, skillRepository, credentialCipher, endpointPolicy, objectMapper, timeout,
                (configuration, request, events) -> new DefaultKnowledgeExtractionAdapter(
                        new OpenJiuwenAgentRuntimeFactory(configuration)).stream(request, events));
    }

    VersionedAgentExecutor(ModelConnectionRepository modelRepository, SkillRepository skillRepository,
            CredentialCipher credentialCipher, ModelEndpointPolicy endpointPolicy, ObjectMapper objectMapper,
            Duration timeout, AgentCall agentCall) {
        this.modelRepository = modelRepository;
        this.skillRepository = skillRepository;
        this.credentialCipher = credentialCipher;
        this.endpointPolicy = endpointPolicy;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.agentCall = agentCall;
    }

    public AgentExecutionResult stream(UUID modelConfigVersionId, UUID skillVersionId,
            AgentExecutionRequest request, Consumer<com.knowledgemeltingpot.workbench.agent.AgentExecutionEvent> events) {
        ModelConfigVersion model = modelRepository.findConfigVersion(modelConfigVersionId)
                .orElseThrow(() -> new IllegalStateException("frozen model configuration version is unavailable"));
        ModelConnection connection = modelRepository.findConnection(model.modelConnectionId())
                .orElseThrow(() -> new IllegalStateException("frozen model connection is unavailable"));
        if (!connection.enabled()) {
            throw new IllegalStateException("frozen model connection is disabled");
        }
        SkillVersion skill = skillRepository.findVersion(skillVersionId)
                .orElseThrow(() -> new IllegalStateException("frozen Skill version is unavailable"));
        var envelope = connection.credentialEnvelope()
                .orElseThrow(() -> new IllegalStateException("frozen model connection has no credential"));
        var validatedEndpoint = endpointPolicy.validate(connection.baseUrl().toString());
        char[] credential = credentialCipher.unseal(connection.id(), envelope);
        try {
            AgentModelConfiguration configuration = AgentModelConfiguration.builder()
                    .provider(ModelProvider.valueOf(connection.provider().name()))
                    .modelName(model.modelId())
                    .apiBase(validatedEndpoint.uri())
                    .apiKey(new String(credential))
                    .timeout(timeout)
                    .temperature(model.temperature().doubleValue())
                    .maxTokens(model.maxOutputTokens())
                    .verifySsl(true)
                    .systemPrompt(systemPrompt(skill))
                    .build();
            return agentCall.stream(configuration, request, events);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    private String systemPrompt(SkillVersion skill) {
        try {
            JsonNode root = objectMapper.readTree(skill.manifestJson());
            JsonNode prompt = root.path("prompt");
            if (prompt.isTextual() && !prompt.asText().isBlank()) {
                return BASE_SYSTEM_PROMPT + "\n" + prompt.asText().strip();
            }
            return BASE_SYSTEM_PROMPT;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted Skill manifest is invalid");
        }
    }

    @FunctionalInterface
    interface AgentCall {
        AgentExecutionResult stream(AgentModelConfiguration configuration, AgentExecutionRequest request,
                Consumer<com.knowledgemeltingpot.workbench.agent.AgentExecutionEvent> events);
    }
}
