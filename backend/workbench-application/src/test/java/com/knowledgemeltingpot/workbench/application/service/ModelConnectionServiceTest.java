package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModelConnectionServiceTest {

    @Test
    void sealsCredentialAndAuditsOnlyNonSecretMetadata() throws Exception {
        String rawSecret = "credential-do-not-log";
        String sealedSecret = "kmp1.sealed-secret";
        ModelConnectionRepository repository = mock(ModelConnectionRepository.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        AuditService audit = mock(AuditService.class);
        when(cipher.seal(any(UUID.class), any(char[].class))).thenReturn(new CredentialEnvelope(sealedSecret));
        when(repository.save(any(ModelConnection.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        ModelEndpointPolicy policy = new ModelEndpointPolicy(Set.of("api.example.com"),
                host -> List.of(InetAddress.getByName("8.8.8.8")));
        ModelConnectionService service = new ModelConnectionService(repository, cipher, policy,
                new PolicyOnlyModelConnectionTestPort(), audit, Clock.fixed(now, ZoneOffset.UTC));
        UUID actorId = UUID.randomUUID();

        ModelConnection connection = service.create("企业网关", ModelProvider.OPENAI_COMPATIBLE,
                "https://api.example.com/v1", rawSecret.toCharArray(), true, actorId, "trace-1");

        assertThat(connection.credentialConfigured()).isTrue();
        assertThat(connection.toString()).doesNotContain(rawSecret, sealedSecret);
        ArgumentCaptor<ModelConnection> saved = ArgumentCaptor.forClass(ModelConnection.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().baseUrl().toString()).isEqualTo("https://api.example.com/v1");
        verify(audit).record(any(), any(), any(), any(), any(), any());
    }
}
