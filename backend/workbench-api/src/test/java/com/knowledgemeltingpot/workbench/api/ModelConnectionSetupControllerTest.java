package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.api.ModelConnectionSetupController.ModelConnectionSetupRequest;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestResult;
import com.knowledgemeltingpot.workbench.application.service.ModelConnectionService;
import com.knowledgemeltingpot.workbench.application.service.ModelConnectionSetupService;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

class ModelConnectionSetupControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @Test
    void returnsTheCreatedArbitraryModelVersionAndTestOutcomeWithoutTheCredential() {
        UUID actorId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        ModelConnectionSetupService setups = mock(ModelConnectionSetupService.class);
        ModelConnectionService modelConnections = mock(ModelConnectionService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        Authentication authentication = mock(Authentication.class);
        ModelConnection untested = connection(connectionId, actorId, ModelConnectionValidationStatus.UNTESTED, null);
        ModelConnection verified = connection(connectionId, actorId,
                ModelConnectionValidationStatus.CONNECTIVITY_VERIFIED, NOW);
        ModelConfigVersion version = new ModelConfigVersion(UUID.randomUUID(), connectionId, 1,
                "MiniMax-M2.5", new BigDecimal("0.2"), 8192, actorId, NOW);
        ModelConnectionTestResult testResult = new ModelConnectionTestResult("CONNECTED", true, true,
                true, "model.connection.verified", NOW);
        ArgumentCaptor<char[]> credential = ArgumentCaptor.forClass(char[].class);
        when(currentUser.id(authentication)).thenReturn(actorId);
        when(setups.configure(eq("MiniMax Token Plan"), eq(ModelProvider.OPENAI_COMPATIBLE),
                eq("https://api.minimaxi.com/v1"), credential.capture(), eq(true),
                eq("MiniMax-M2.5"), eq(false), eq(actorId), any())).thenReturn(
                        new ModelConnectionSetupService.Configuration(untested, version));
        when(modelConnections.test(eq(connectionId), eq(actorId), any())).thenReturn(testResult);
        when(modelConnections.get(connectionId)).thenReturn(verified);
        ModelConnectionSetupController controller = new ModelConnectionSetupController(
                setups, modelConnections, currentUser);

        var response = controller.configure(new ModelConnectionSetupRequest("MiniMax Token Plan",
                ModelProvider.OPENAI_COMPATIBLE, "https://api.minimaxi.com/v1", "token-plan-key",
                true, "MiniMax-M2.5", false), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().connection().validationStatus())
                .isEqualTo(ModelConnectionValidationStatus.CONNECTIVITY_VERIFIED);
        assertThat(response.getBody().configVersion().modelId()).isEqualTo("MiniMax-M2.5");
        assertThat(response.getBody().connectionTest().connectivityVerified()).isTrue();
        assertThat(credential.getValue()).containsOnly('\0');
        assertThat(response.getBody().toString()).doesNotContain("token-plan-key");
    }

    private static ModelConnection connection(UUID id, UUID actorId,
            ModelConnectionValidationStatus status, Instant lastValidatedAt) {
        return new ModelConnection(id, "MiniMax Token Plan", ModelProvider.OPENAI_COMPATIBLE,
                URI.create("https://api.minimaxi.com/v1"), Optional.of(new CredentialEnvelope("kmp1.test")),
                true, status, lastValidatedAt, actorId, NOW, NOW);
    }
}
