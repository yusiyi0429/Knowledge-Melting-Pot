package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.EmbeddingProfileVersionRepository;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmbeddingProfileServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void activatesOnlyAConnectivityVerifiedConnectionAndAuditsNonSecretMetadata() {
        UUID connectionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ModelConnectionRepository models = mock(ModelConnectionRepository.class);
        EmbeddingProfileVersionRepository profiles = mock(EmbeddingProfileVersionRepository.class);
        AuditService audit = mock(AuditService.class);
        when(models.findConnection(connectionId)).thenReturn(Optional.of(connection(
                connectionId, ModelConnectionValidationStatus.CONNECTIVITY_VERIFIED)));
        when(profiles.insertAndActivate(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        EmbeddingProfileService service = new EmbeddingProfileService(profiles, models, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));

        EmbeddingProfileVersion created = service.createAndActivate(connectionId, "text-embedding-v4",
                1024, "2026-08", "L2", "COSINE", actorId, "trace-vector");

        assertThat(created.modelConnectionId()).isEqualTo(connectionId);
        assertThat(created.provider()).isEqualTo(ModelProvider.DASHSCOPE.name());
        assertThat(created.active()).isTrue();
        ArgumentCaptor<EmbeddingProfileVersion> saved = ArgumentCaptor.forClass(EmbeddingProfileVersion.class);
        verify(profiles).insertAndActivate(saved.capture(), org.mockito.ArgumentMatchers.eq(actorId),
                org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(saved.getValue().dimension()).isEqualTo(1024);
        verify(audit).record(org.mockito.ArgumentMatchers.eq(actorId),
                org.mockito.ArgumentMatchers.eq("EMBEDDING_PROFILE_ACTIVATED"),
                org.mockito.ArgumentMatchers.eq("EMBEDDING_PROFILE"), any(), any(),
                org.mockito.ArgumentMatchers.eq("trace-vector"));
    }

    @Test
    void rejectsUntestedConnectionBeforeCreatingAnIndexProfile() {
        UUID connectionId = UUID.randomUUID();
        ModelConnectionRepository models = mock(ModelConnectionRepository.class);
        EmbeddingProfileVersionRepository profiles = mock(EmbeddingProfileVersionRepository.class);
        when(models.findConnection(connectionId)).thenReturn(Optional.of(connection(
                connectionId, ModelConnectionValidationStatus.UNTESTED)));
        EmbeddingProfileService service = new EmbeddingProfileService(profiles, models,
                mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.createAndActivate(connectionId, "text-embedding-v4",
                1024, "2026-08", "L2", "COSINE", UUID.randomUUID(), "trace-vector"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectivity-verified");
        verify(profiles, never()).insertAndActivate(any(), any(), any());
    }

    @Test
    void rejectsDimensionsThatPgvectorCannotIndexWithVectorHnsw() {
        EmbeddingProfileVersionRepository profiles = mock(EmbeddingProfileVersionRepository.class);
        EmbeddingProfileService service = new EmbeddingProfileService(profiles,
                mock(ModelConnectionRepository.class), mock(AuditService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.createAndActivate(UUID.randomUUID(), "text-embedding-3-large",
                3072, "2026-08", "L2", "COSINE", UUID.randomUUID(), "trace-vector"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 2000");
        verify(profiles, never()).insertAndActivate(any(), any(), any());
    }

    private static ModelConnection connection(UUID id, ModelConnectionValidationStatus status) {
        return new ModelConnection(id, "embedding", ModelProvider.DASHSCOPE,
                URI.create("https://dashscope.aliyuncs.com/api/v1"),
                Optional.of(new CredentialEnvelope("kmp1.encrypted")), true,
                status, status == ModelConnectionValidationStatus.UNTESTED ? null : NOW,
                UUID.randomUUID(), NOW, NOW);
    }
}
