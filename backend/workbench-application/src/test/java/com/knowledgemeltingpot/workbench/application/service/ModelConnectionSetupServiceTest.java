package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelConnectionSetupServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @Test
    void configuresAnArbitraryModelAndItsExactIntranetEndpointInOneTransaction() {
        UUID actorId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        ModelEndpointRuleService endpointRules = mock(ModelEndpointRuleService.class);
        ModelConnectionService modelConnections = mock(ModelConnectionService.class);
        ModelConnection connection = connection(connectionId, actorId);
        ModelConfigVersion version = new ModelConfigVersion(UUID.randomUUID(), connectionId, 1,
                "bank-model-32b-v3", new BigDecimal("0.2"), 8192, actorId, NOW);
        when(modelConnections.create(eq("内网推理网关"), eq(ModelProvider.OPENAI_COMPATIBLE),
                eq("http://llm.bank.local:8000/v1"), any(char[].class), eq(true), eq(actorId), eq("trace-1")))
                .thenReturn(connection);
        when(modelConnections.createVersion(connectionId, "bank-model-32b-v3", new BigDecimal("0.2"),
                8192, actorId, "trace-1")).thenReturn(version);
        ModelConnectionSetupService service = new ModelConnectionSetupService(endpointRules, modelConnections);

        var configured = service.configure("内网推理网关", ModelProvider.OPENAI_COMPATIBLE,
                "http://llm.bank.local:8000/v1", "local-key".toCharArray(), true,
                "bank-model-32b-v3", true, actorId, "trace-1");

        assertThat(configured.connection()).isSameAs(connection);
        assertThat(configured.configVersion()).isSameAs(version);
        verify(endpointRules).ensureHost("llm.bank.local", 8000, true, true, actorId, "trace-1");
    }

    @Test
    void rejectsNonHttpUrlsBeforePersistingAnything() {
        ModelEndpointRuleService endpointRules = mock(ModelEndpointRuleService.class);
        ModelConnectionService modelConnections = mock(ModelConnectionService.class);
        ModelConnectionSetupService service = new ModelConnectionSetupService(endpointRules, modelConnections);

        assertThatThrownBy(() -> service.configure("invalid", ModelProvider.OPENAI_COMPATIBLE,
                "file:///tmp/model", null, true, "any-model", false, UUID.randomUUID(), "trace-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
        verifyNoInteractions(endpointRules, modelConnections);
    }

    private static ModelConnection connection(UUID id, UUID actorId) {
        return new ModelConnection(id, "内网推理网关", ModelProvider.OPENAI_COMPATIBLE,
                URI.create("http://llm.bank.local:8000/v1"),
                Optional.of(new CredentialEnvelope("kmp1.test")), true,
                ModelConnectionValidationStatus.UNTESTED, null, actorId, NOW, NOW);
    }
}
