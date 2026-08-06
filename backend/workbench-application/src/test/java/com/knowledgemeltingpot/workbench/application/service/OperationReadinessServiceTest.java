package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.ExplorationRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelectionPort;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.ExplorationSession;
import com.knowledgemeltingpot.workbench.domain.ExplorationStatus;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExtractionRoundStatus;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperationReadinessServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");
    private final UUID sceneId = UUID.randomUUID();
    private final UUID subSceneId = UUID.randomUUID();
    private final UUID roundId = UUID.randomUUID();
    private AgentConfigurationService agents;
    private ExplorationRepository explorations;
    private SceneRepository scenes;
    private MaterialSelectionPort materials;
    private OperationReadinessService service;

    @BeforeEach
    void setUp() {
        agents = mock(AgentConfigurationService.class);
        explorations = mock(ExplorationRepository.class);
        scenes = mock(SceneRepository.class);
        materials = mock(MaterialSelectionPort.class);
        service = new OperationReadinessService(agents, explorations, scenes, materials,
                mock(DocumentService.class), mock(AssetRepository.class));
    }

    @Test
    void reportsGlobalAgentAndMaterialBlockersBeforeSceneExploration() {
        UUID sessionId = UUID.randomUUID();
        when(agents.resolveGlobal(AgentRole.SCENE_EXPLORER)).thenReturn(configuration(
                AgentRole.SCENE_EXPLORER, false));
        when(explorations.find(sessionId)).thenReturn(Optional.of(new ExplorationSession(sessionId, "主题",
                ExplorationStatus.DRAFT, null, null, null, null, "", 0, UUID.randomUUID(), NOW, NOW)));
        when(explorations.findMaterials(sessionId)).thenReturn(List.of());

        var report = service.check(OperationReadinessService.Operation.SCENE_EXPLORE,
                sessionId, null, null, null);

        assertThat(report.ready()).isFalse();
        assertThat(report.blockers()).extracting(OperationReadinessService.Blocker::code)
                .containsExactly("AGENT_CONFIGURATION_INCOMPLETE", "EXPLORATION_MATERIALS_REQUIRED");
        assertThat(report.agents()).singleElement().satisfies(requirement -> {
            assertThat(requirement.role()).isEqualTo(AgentRole.SCENE_EXPLORER);
            assertThat(requirement.configured()).isFalse();
        });
    }

    @Test
    void reportsReadyWhenExtractionAgentContextAndMaterialAreValid() {
        when(scenes.findScene(sceneId)).thenReturn(Optional.of(new Scene(sceneId, "场景", "", NOW, NOW)));
        when(scenes.findSubScene(subSceneId)).thenReturn(Optional.of(
                new SubScene(subSceneId, sceneId, "子场景", "", NOW, NOW)));
        when(scenes.findRound(roundId)).thenReturn(Optional.of(new ExtractionRound(roundId, subSceneId, 1,
                ExtractionRoundStatus.DRAFT, NOW, NOW)));
        when(agents.resolve(sceneId, subSceneId)).thenReturn(List.of(configuration(
                AgentRole.KNOWLEDGE_EXTRACTOR, true)));
        when(materials.findForExtraction(roundId, subSceneId)).thenReturn(List.of(selection()));

        var report = service.check(OperationReadinessService.Operation.EXTRACT,
                null, sceneId, subSceneId, roundId);

        assertThat(report.ready()).isTrue();
        assertThat(report.blockers()).isEmpty();
        assertThat(report.agents()).singleElement()
                .extracting(OperationReadinessService.AgentRequirement::configured).isEqualTo(true);
    }

    private AgentConfigurationService.EffectiveAgentConfiguration configuration(AgentRole role,
            boolean configured) {
        return new AgentConfigurationService.EffectiveAgentConfiguration(role, role.displayName(), role.stage(),
                configured, configured ? UUID.randomUUID() : null, configured ? UUID.randomUUID() : null,
                "{}", "a".repeat(64), configured ? UUID.randomUUID() : null,
                null, null, null, "TEMPLATE", List.of());
    }

    private MaterialSelection selection() {
        UUID materialId = UUID.randomUUID();
        Material material = new Material(materialId, "source.txt", MaterialFormat.TXT, "text/plain", "object",
                "a".repeat(64), 20, MaterialStatus.READY, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), materialId, roundId, subSceneId,
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, false, true, NOW);
        return new MaterialSelection(material, binding);
    }
}
