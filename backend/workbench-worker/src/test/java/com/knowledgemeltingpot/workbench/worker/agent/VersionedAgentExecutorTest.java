package com.knowledgemeltingpot.workbench.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VersionedAgentExecutorTest {
    @Test
    void resolvesFrozenVersionsAndWipesTheDecryptedCredentialAfterTheCall() throws Exception {
        UUID connectionId = UUID.randomUUID();
        UUID modelVersionId = UUID.randomUUID();
        UUID skillVersionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        ModelConnectionRepository models = mock(ModelConnectionRepository.class);
        SkillRepository skills = mock(SkillRepository.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        CredentialEnvelope envelope = new CredentialEnvelope("kmp1.encrypted");
        ModelConfigVersion model = new ModelConfigVersion(modelVersionId, connectionId, 3, "qwen-plus",
                new BigDecimal("0.30"), 4096, actorId, now);
        ModelConnection connection = new ModelConnection(connectionId, "受控网关", ModelProvider.DASHSCOPE,
                URI.create("https://models.example.com/v1"), Optional.of(envelope), true,
                ModelConnectionValidationStatus.UNTESTED, null, actorId, now, now);
        SkillVersion skill = new SkillVersion(skillVersionId, UUID.randomUUID(), 2,
                "{\"executionMode\":\"RESOURCE_ONLY\",\"prompt\":\"只返回结构化结果\"}",
                "a".repeat(64), actorId, now);
        char[] cleartext = "short-lived-key".toCharArray();
        when(models.findConfigVersion(modelVersionId)).thenReturn(Optional.of(model));
        when(models.findConnection(connectionId)).thenReturn(Optional.of(connection));
        when(skills.findVersion(skillVersionId)).thenReturn(Optional.of(skill));
        when(cipher.unseal(connectionId, envelope)).thenReturn(cleartext);
        ModelEndpointPolicy endpointPolicy = new ModelEndpointPolicy(Set.of("models.example.com"),
                ignored -> List.of(InetAddress.getByName("8.8.8.8")));
        Instant completed = now.plusSeconds(1);
        VersionedAgentExecutor executor = new VersionedAgentExecutor(models, skills, cipher, endpointPolicy,
                new ObjectMapper(), Duration.ofSeconds(30), (configuration, request, events) -> {
                    assertThat(configuration.provider().name()).isEqualTo("DASHSCOPE");
                    assertThat(configuration.modelName()).isEqualTo("qwen-plus");
                    assertThat(configuration.systemPrompt()).contains("只返回结构化结果");
                    assertThat(configuration.temperature()).isEqualTo(0.3);
                    return new AgentExecutionResult("job-1", "session-1", AgentExecutionStatus.COMPLETED,
                            "{}", "", "", now, completed);
                });

        AgentExecutionResult result = executor.stream(modelVersionId, skillVersionId,
                new AgentExecutionRequest("job", "system", "prompt", AgentExecutionMode.WORKFLOW), ignored -> { });

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(cleartext).containsOnly('\0');
        verify(cipher).unseal(connectionId, envelope);
    }
}
