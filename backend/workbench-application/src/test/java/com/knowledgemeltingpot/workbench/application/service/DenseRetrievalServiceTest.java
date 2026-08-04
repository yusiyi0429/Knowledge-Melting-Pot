package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.DenseRetrievalRepository;
import com.knowledgemeltingpot.workbench.application.port.EmbeddingPort;
import com.knowledgemeltingpot.workbench.application.port.EmbeddingProfileVersionRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExtractionRoundStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DenseRetrievalServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void embedsTrimmedChineseQueryAndClampsRequestedResultCount() {
        UUID roundId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        EmbeddingProfileVersion profile = profile();
        EmbeddingProfileVersionRepository profiles = mock(EmbeddingProfileVersionRepository.class);
        SceneRepository scenes = mock(SceneRepository.class);
        EmbeddingPort provider = mock(EmbeddingPort.class);
        DenseRetrievalRepository retrieval = mock(DenseRetrievalRepository.class);
        when(scenes.findRound(roundId)).thenReturn(Optional.of(new ExtractionRound(roundId, subSceneId,
                1, ExtractionRoundStatus.DRAFT, NOW, NOW)));
        when(profiles.findActive()).thenReturn(Optional.of(profile));
        when(provider.provider()).thenReturn("DASHSCOPE");
        when(provider.embedQuery("风险等级如何判断？", profile)).thenReturn(List.of(0.6f, 0.8f));
        when(retrieval.searchKnowledge(roundId, subSceneId, profile, List.of(0.6f, 0.8f), 50))
                .thenReturn(List.of());
        DenseRetrievalService service = new DenseRetrievalService(profiles, List.of(provider), retrieval, scenes);

        assertThat(service.searchKnowledge(roundId, subSceneId, "  风险等级如何判断？  ", 100)).isEmpty();

        verify(provider).embedQuery("风险等级如何判断？", profile);
        verify(retrieval).searchKnowledge(roundId, subSceneId, profile, List.of(0.6f, 0.8f), 50);
    }

    @Test
    void rejectsCrossSubSceneRoundBeforeCallingProvider() {
        UUID roundId = UUID.randomUUID();
        SceneRepository scenes = mock(SceneRepository.class);
        EmbeddingPort provider = mock(EmbeddingPort.class);
        when(scenes.findRound(roundId)).thenReturn(Optional.of(new ExtractionRound(roundId, UUID.randomUUID(),
                1, ExtractionRoundStatus.DRAFT, NOW, NOW)));
        DenseRetrievalService service = new DenseRetrievalService(
                mock(EmbeddingProfileVersionRepository.class), List.of(provider),
                mock(DenseRetrievalRepository.class), scenes);

        assertThatThrownBy(() -> service.searchKnowledge(roundId, UUID.randomUUID(), "风险查询", 10))
                .isInstanceOf(NotFoundException.class);
        verify(provider, never()).embedQuery(any(), any());
    }

    @Test
    void reportsMissingActiveProfileAsConfigurationConflict() {
        UUID roundId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        SceneRepository scenes = mock(SceneRepository.class);
        EmbeddingProfileVersionRepository profiles = mock(EmbeddingProfileVersionRepository.class);
        when(scenes.findRound(roundId)).thenReturn(Optional.of(new ExtractionRound(roundId, subSceneId,
                1, ExtractionRoundStatus.DRAFT, NOW, NOW)));
        when(profiles.findActive()).thenReturn(Optional.empty());
        DenseRetrievalService service = new DenseRetrievalService(profiles, List.of(),
                mock(DenseRetrievalRepository.class), scenes);

        assertThatThrownBy(() -> service.searchKnowledge(roundId, subSceneId, "风险查询", 10))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no active embedding profile");
    }

    private static EmbeddingProfileVersion profile() {
        return new EmbeddingProfileVersion(UUID.randomUUID(), UUID.randomUUID(), "DASHSCOPE",
                "text-embedding-v4", 2, "2026-08", "L2", "COSINE", true, NOW);
    }
}
