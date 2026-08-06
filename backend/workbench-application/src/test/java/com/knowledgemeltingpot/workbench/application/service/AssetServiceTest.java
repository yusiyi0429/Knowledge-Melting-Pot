package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelectionPort;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.AssetStatus;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExtractionRoundStatus;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Mock
    private AssetRepository assets;
    @Mock
    private SceneService scenes;
    @Mock
    private DocumentService documents;
    @Mock
    private JobService jobs;
    @Mock
    private MaterialSelectionPort materialSelection;
    @Mock
    private SceneRepository sceneRepository;
    @Mock
    private ObjectStoragePort storage;
    @Mock
    private AgentConfigurationService agentConfigurations;

    private AssetService service;
    private UUID subSceneId;
    private SubScene subScene;

    @BeforeEach
    void setUp() {
        service = new AssetService(assets, scenes, documents, jobs, materialSelection, sceneRepository,
                Optional.of(storage), Clock.fixed(NOW, ZoneOffset.UTC), agentConfigurations);
        UUID sceneId = UUID.randomUUID();
        subSceneId = UUID.randomUUID();
        subScene = new SubScene(subSceneId, sceneId, "Sub", "", NOW, NOW);
        org.mockito.Mockito.lenient().when(scenes.getSubScene(subSceneId)).thenReturn(subScene);
        org.mockito.Mockito.lenient().when(agentConfigurations.resolve(sceneId, subSceneId)).thenReturn(
                java.util.Arrays.stream(AgentRole.values()).map(this::configured).toList());
    }

    @Test
    void requestGenerationRejectsUnfinalizedDocumentRevision() {
        DocumentRevision revision = revision(false);
        when(documents.getRevision(revision.id())).thenReturn(revision);

        assertThatThrownBy(() -> service.requestGeneration(subSceneId, revision.id(), Set.of(AssetType.QA_PAIRS),
                ACTOR_ID, null, "trace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be finalized");
    }

    @Test
    void requestGenerationSubmitsJobForFinalizedRevision() {
        DocumentRevision revision = revision(true);
        when(documents.getRevision(revision.id())).thenReturn(revision);
        Job job = new Job(UUID.randomUUID(), JobType.GENERATE_ALL, "SUB_SCENE", subSceneId, JobStatus.QUEUED,
                0, 0, "{}", "", "", "", ACTOR_ID, NOW, NOW);

        service.requestGeneration(subSceneId, revision.id(), null, ACTOR_ID, null, "trace");

        verify(jobs).submit(eq(JobType.GENERATE_ALL), eq("SUB_SCENE"), eq(subSceneId), anyMap(),
                eq(ACTOR_ID), eq(null), eq("trace"));
    }

    @Test
    void markBlockedDelegatesToRepository() {
        Asset asset = asset(AssetStatus.GENERATING);
        when(assets.markBlocked(asset.id(), "no holdout", NOW)).thenReturn(asset);

        Asset blocked = service.markBlocked(asset.id(), "no holdout");

        verify(assets).markBlocked(asset.id(), "no holdout", NOW);
        assertThat(blocked).isNotNull();
    }

    @Test
    void holdoutSelectionUsesLatestRoundForTheSubScene() {
        UUID otherRound = UUID.randomUUID();
        UUID latestRound = UUID.randomUUID();
        when(sceneRepository.findRoundsByScene(subScene.sceneId())).thenReturn(List.of(
                new ExtractionRound(otherRound, UUID.randomUUID(), 1, ExtractionRoundStatus.DRAFT, NOW, NOW),
                new ExtractionRound(latestRound, subSceneId, 2, ExtractionRoundStatus.DRAFT, NOW, NOW)));
        Material material = new Material(UUID.randomUUID(), "holdout.xlsx", MaterialFormat.XLSX,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "holdout/" + UUID.randomUUID(), "c".repeat(64), 10, MaterialStatus.READY, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), material.id(), latestRound, subSceneId,
                MaterialPartition.LABELED_HOLDOUT, MaterialShareScope.ROUND, false, true, NOW);
        when(materialSelection.findForEvaluation(latestRound, subSceneId))
                .thenReturn(List.of(new MaterialSelection(material, binding)));

        List<MaterialSelection> selected = service.holdoutSelection(subSceneId);

        assertThat(selected).singleElement().satisfies(selection -> {
            assertThat(selection.binding().partition()).isEqualTo(MaterialPartition.LABELED_HOLDOUT);
        });
    }

    @Test
    void downloadUrlRequiresAReadyAsset() {
        Asset failed = asset(AssetStatus.FAILED);
        when(assets.findById(failed.id())).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> service.downloadUrl(failed.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void downloadUrlPresignsAssetsZoneForKey() throws Exception {
        Asset ready = new Asset(UUID.randomUUID(), subSceneId, AssetType.RULE_CATALOG, 2, AssetStatus.READY,
                UUID.randomUUID(), "assets/" + subSceneId + "/rule_catalog/v2/bundle.zip", "abc", "", NOW, NOW);
        when(assets.findById(ready.id())).thenReturn(Optional.of(ready));
        when(storage.presignDownload(eq(ObjectStoragePort.StorageZone.ASSETS), eq(ready.objectKey()), any()))
                .thenReturn(new URL("https://minio.example/assets/signed"));

        assertThat(service.downloadUrl(ready.id()).toString()).isEqualTo("https://minio.example/assets/signed");
        verify(storage).presignDownload(eq(ObjectStoragePort.StorageZone.ASSETS), eq(ready.objectKey()), any());
    }

    private DocumentRevision revision(boolean finalized) {
        return new DocumentRevision(UUID.randomUUID(), subSceneId, subSceneId, 1, null,
                "# 标题\n[SRC-001] 内容", "hash", "", finalized,
                finalized ? ACTOR_ID : null, finalized ? NOW : null, ACTOR_ID, NOW);
    }

    private Asset asset(AssetStatus status) {
        return new Asset(UUID.randomUUID(), subSceneId, AssetType.QA_PAIRS, 1, status, null, "", "", "", NOW, NOW);
    }

    private AgentConfigurationService.EffectiveAgentConfiguration configured(AgentRole role) {
        return new AgentConfigurationService.EffectiveAgentConfiguration(role, role.displayName(), role.stage(),
                true, UUID.randomUUID(), UUID.randomUUID(), "{}", "a".repeat(64), UUID.randomUUID(),
                null, null, null, "TEMPLATE", List.of());
    }
}
