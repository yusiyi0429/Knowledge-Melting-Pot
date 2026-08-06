package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExtractionRoundStatus;
import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SceneServiceRoundTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void creatingASubSceneAlsoCreatesItsInitialDraftRound() {
        UUID sceneId = UUID.randomUUID();
        SceneRepository scenes = mock(SceneRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        when(scenes.findScene(sceneId)).thenReturn(Optional.of(scene(sceneId)));
        when(scenes.save(any(SubScene.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SceneService service = service(scenes, assets);

        SubScene subScene = service.createSubScene(sceneId, "开户", "", UUID.randomUUID(), "trace");

        verify(assets).ensurePlaceholders(subScene.id(), NOW);
        verify(scenes).createNextRound(eq(subScene.id()), any(UUID.class), eq(NOW));
    }

    @Test
    void createsTheNextRoundOnlyForASubSceneOwnedByTheScene() {
        UUID sceneId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        SceneRepository scenes = mock(SceneRepository.class);
        when(scenes.findScene(sceneId)).thenReturn(Optional.of(scene(sceneId)));
        when(scenes.findSubScene(subSceneId)).thenReturn(Optional.of(
                new SubScene(subSceneId, sceneId, "开户", "", NOW, NOW)));
        when(scenes.createNextRound(eq(subSceneId), any(UUID.class), eq(NOW)))
                .thenAnswer(invocation -> new ExtractionRound(invocation.<UUID>getArgument(1), subSceneId, 2,
                        ExtractionRoundStatus.DRAFT, NOW, NOW));
        SceneService service = service(scenes, mock(AssetRepository.class));

        ExtractionRound round = service.createRound(sceneId, subSceneId, UUID.randomUUID(), "trace");

        assertThat(round.roundNumber()).isEqualTo(2);
        assertThat(round.status()).isEqualTo(ExtractionRoundStatus.DRAFT);
    }

    @Test
    void rejectsASubSceneFromAnotherScene() {
        UUID sceneId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        SceneRepository scenes = mock(SceneRepository.class);
        when(scenes.findScene(sceneId)).thenReturn(Optional.of(scene(sceneId)));
        when(scenes.findSubScene(subSceneId)).thenReturn(Optional.of(
                new SubScene(subSceneId, UUID.randomUUID(), "其他场景", "", NOW, NOW)));
        SceneService service = service(scenes, mock(AssetRepository.class));

        assertThatThrownBy(() -> service.createRound(sceneId, subSceneId, UUID.randomUUID(), "trace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void deletingASceneArchivesItWithoutDestroyingItsLineage() {
        UUID sceneId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SceneRepository scenes = mock(SceneRepository.class);
        AuditService audit = mock(AuditService.class);
        when(scenes.findScene(sceneId)).thenReturn(Optional.of(scene(sceneId)));
        when(scenes.archiveScene(sceneId, actorId, NOW)).thenReturn(true);
        SceneService service = new SceneService(scenes, mock(AssetRepository.class), audit,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.delete(sceneId, actorId, "trace");

        verify(scenes).archiveScene(sceneId, actorId, NOW);
        verify(audit).record(eq(actorId), eq("SCENE_ARCHIVED"), eq("SCENE"), eq(sceneId), any(), eq("trace"));
    }

    private SceneService service(SceneRepository scenes, AssetRepository assets) {
        return new SceneService(scenes, assets, mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Scene scene(UUID id) {
        return new Scene(id, "贷款", "", NOW, NOW);
    }
}
