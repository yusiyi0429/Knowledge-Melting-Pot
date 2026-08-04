package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.ChunkRepository;
import com.knowledgemeltingpot.workbench.application.port.ContextBudget;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelectionPort;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.port.TrustedContext;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExtractionRoundStatus;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialSelectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Mock
    private MaterialSelectionPort port;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private SceneRepository sceneRepository;
    private MaterialSelectionService service;
    private UUID roundId;
    private UUID subSceneId;
    private UUID sceneId;

    @BeforeEach
    void setUp() {
        service = new MaterialSelectionService(port, chunkRepository, sceneRepository);
        roundId = UUID.randomUUID();
        subSceneId = UUID.randomUUID();
        sceneId = UUID.randomUUID();
        lenient().when(sceneRepository.findSubScene(any())).thenReturn(java.util.Optional.empty());
        lenient().when(sceneRepository.findSubScene(subSceneId))
                .thenReturn(java.util.Optional.of(new SubScene(subSceneId, sceneId, "sub", "", NOW, NOW)));
        lenient().when(sceneRepository.findRound(roundId))
                .thenReturn(java.util.Optional.of(new ExtractionRound(
                        roundId, subSceneId, 1, ExtractionRoundStatus.DRAFT, NOW, NOW)));
    }

    @Test
    void extractionFailsClosedIfPortEverReturnsHoldout() {
        when(port.findForExtraction(roundId, subSceneId))
                .thenReturn(List.of(selection(MaterialPartition.LABELED_HOLDOUT, false, subSceneId, MaterialShareScope.ROUND)));

        assertThatThrownBy(() -> service.forExtraction(roundId, subSceneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holdout");
    }

    @Test
    void qaFailsClosedIfPortEverReturnsHoldout() {
        when(port.findForQa(roundId, subSceneId))
                .thenReturn(List.of(selection(MaterialPartition.LABELED_HOLDOUT, false, subSceneId, MaterialShareScope.ROUND)));

        assertThatThrownBy(() -> service.forQa(roundId, subSceneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holdout");
    }

    @Test
    void alignmentRequiresNonHoldoutRegulatorySource() {
        when(port.findForAlignment(roundId, subSceneId))
                .thenReturn(List.of(selection(MaterialPartition.SOURCE, false, subSceneId, MaterialShareScope.ROUND)));

        assertThatThrownBy(() -> service.forAlignment(roundId, subSceneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-regulatory");
    }

    @Test
    void evaluationFailsClosedIfPortEverReturnsTrainingMaterial() {
        when(port.findForEvaluation(roundId, subSceneId))
                .thenReturn(List.of(selection(MaterialPartition.LABELED_TRAIN, false, subSceneId, MaterialShareScope.ROUND)));

        assertThatThrownBy(() -> service.forEvaluation(roundId, subSceneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-holdout");
    }

    @Test
    void validKnowledgeAndEvaluationSelectionsRemainSeparate() {
        MaterialSelection knowledge = selection(MaterialPartition.LABELED_TRAIN, false, subSceneId, MaterialShareScope.ROUND);
        MaterialSelection evaluation = selection(MaterialPartition.LABELED_HOLDOUT, false, subSceneId, MaterialShareScope.ROUND);
        when(port.findForExtraction(roundId, subSceneId)).thenReturn(List.of(knowledge));
        when(port.findForEvaluation(roundId, subSceneId)).thenReturn(List.of(evaluation));

        assertThat(service.forExtraction(roundId, subSceneId)).containsExactly(knowledge);
        assertThat(service.forEvaluation(roundId, subSceneId)).containsExactly(evaluation);
    }

    @Test
    void knowledgeContextLoadsChunksFromTheDatabaseOnly() {
        MaterialSelection selection = selection(MaterialPartition.SOURCE, false, subSceneId, MaterialShareScope.ROUND);
        when(port.findForExtraction(roundId, subSceneId)).thenReturn(List.of(selection));
        MaterialChunk chunk = chunk(selection.material().id(), 0, "rule one");
        when(chunkRepository.findForMaterials(List.of(selection.material().id())))
                .thenReturn(Map.of(selection.material().id(), List.of(chunk)));

        List<TrustedContext> contexts = service.knowledgeContext(roundId, subSceneId, new ContextBudget(10, 10_000));

        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).selection()).isEqualTo(selection);
        assertThat(contexts.get(0).chunks()).containsExactly(chunk);
    }

    @Test
    void knowledgeContextSkipsReadyMaterialWithoutChunks() {
        MaterialSelection selection = selection(MaterialPartition.SOURCE, false, subSceneId, MaterialShareScope.ROUND);
        when(port.findForExtraction(roundId, subSceneId)).thenReturn(List.of(selection));
        when(chunkRepository.findForMaterials(List.of(selection.material().id()))).thenReturn(Map.of());

        List<TrustedContext> contexts = service.knowledgeContext(roundId, subSceneId, ContextBudget.defaults());

        assertThat(contexts).isEmpty();
    }

    @Test
    void evaluationContextNeverContainsKnowledgeChunks() {
        MaterialSelection selection = selection(MaterialPartition.LABELED_HOLDOUT, false, subSceneId, MaterialShareScope.ROUND);
        when(port.findForEvaluation(roundId, subSceneId)).thenReturn(List.of(selection));
        MaterialChunk chunk = chunk(selection.material().id(), 0, "holdout row");
        when(chunkRepository.findForMaterials(List.of(selection.material().id())))
                .thenReturn(Map.of(selection.material().id(), List.of(chunk)));

        List<TrustedContext> contexts = service.evaluationContext(roundId, subSceneId, ContextBudget.defaults());

        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).selection().binding().partition()).isEqualTo(MaterialPartition.LABELED_HOLDOUT);
    }

    @Test
    void topKAndCharBudgetAreEnforced() {
        MaterialSelection first = selection(MaterialPartition.SOURCE, false, subSceneId, MaterialShareScope.ROUND);
        MaterialSelection second = selection(MaterialPartition.SOURCE, false, subSceneId, MaterialShareScope.ROUND);
        when(port.findForExtraction(roundId, subSceneId)).thenReturn(List.of(first, second));
        MaterialChunk firstChunk = chunk(first.material().id(), 0, "a".repeat(50));
        MaterialChunk secondChunk = chunk(second.material().id(), 0, "b".repeat(50));
        when(chunkRepository.findForMaterials(List.of(first.material().id(), second.material().id())))
                .thenReturn(Map.of(first.material().id(), List.of(firstChunk),
                        second.material().id(), List.of(secondChunk)));

        List<TrustedContext> contexts = service.knowledgeContext(roundId, subSceneId, new ContextBudget(1, 10_000));

        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).selection()).isEqualTo(first);
    }

    @Test
    void subsceneScopeBindingFromAnotherSubsceneIsRejected() {
        UUID otherSubSceneId = UUID.randomUUID();
        when(sceneRepository.findSubScene(otherSubSceneId))
                .thenReturn(java.util.Optional.of(new SubScene(otherSubSceneId, sceneId, "other", "", NOW, NOW)));
        MaterialSelection selection = selection(
                MaterialPartition.SOURCE, false, otherSubSceneId, MaterialShareScope.SUBSCENE);
        when(port.findForExtraction(roundId, subSceneId)).thenReturn(List.of(selection));

        assertThatThrownBy(() -> service.knowledgeContext(roundId, subSceneId, ContextBudget.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the requested sub-scene");
    }

    @Test
    void shareScopeSubsceneBindingFromAnotherRoundIsAccepted() {
        UUID otherRound = UUID.randomUUID();
        when(sceneRepository.findRound(otherRound)).thenReturn(java.util.Optional.of(new ExtractionRound(
                otherRound, subSceneId, 2, ExtractionRoundStatus.DRAFT, NOW, NOW)));
        MaterialSelection selection = selection(MaterialPartition.SOURCE, false, subSceneId, MaterialShareScope.SUBSCENE);
        // SUBSCENE-scope bindings may carry a different round; same scene is required.
        when(port.findForExtraction(otherRound, subSceneId)).thenReturn(List.of(selection));
        MaterialChunk chunk = chunk(selection.material().id(), 0, "shared across rounds");
        when(chunkRepository.findForMaterials(List.of(selection.material().id())))
                .thenReturn(Map.of(selection.material().id(), List.of(chunk)));

        List<TrustedContext> contexts = service.knowledgeContext(otherRound, subSceneId, ContextBudget.defaults());

        assertThat(contexts).hasSize(1);
    }

    @Test
    void roundScopeBindingFromAnotherRoundIsRejected() {
        UUID otherRound = UUID.randomUUID();
        when(sceneRepository.findRound(otherRound)).thenReturn(java.util.Optional.of(new ExtractionRound(
                otherRound, subSceneId, 2, ExtractionRoundStatus.DRAFT, NOW, NOW)));
        MaterialSelection selection = selection(MaterialPartition.SOURCE, false, subSceneId, MaterialShareScope.ROUND);
        when(port.findForExtraction(otherRound, subSceneId)).thenReturn(List.of(selection));

        assertThatThrownBy(() -> service.knowledgeContext(otherRound, subSceneId, ContextBudget.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the requested round");
    }

    @Test
    void contextBudgetRejectsUnboundedInput() {
        assertThatThrownBy(() -> new ContextBudget(0, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextBudget(101, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextBudget(10, 10_000_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MaterialSelection selection(MaterialPartition partition, boolean regulatorySource, UUID bindingSubScene,
            MaterialShareScope shareScope) {
        UUID materialId = UUID.randomUUID();
        Material material = new Material(materialId, "source.pdf", MaterialFormat.PDF, "application/pdf",
                "quarantine/" + materialId, "a".repeat(64), 100, MaterialStatus.READY, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), materialId, roundId, bindingSubScene, partition,
                shareScope, regulatorySource, true, NOW);
        return new MaterialSelection(material, binding);
    }

    private MaterialChunk chunk(UUID materialId, int ordinal, String content) {
        return MaterialChunk.fromParsed(UUID.randomUUID(), UUID.randomUUID(), ordinal,
                "SRC-" + materialId.toString().substring(0, 8) + "-" + ordinal,
                new ChunkLocator(ChunkLocator.LocatorType.TXT_LINES, null, null, null, null, null, null, null,
                        null, ordinal, ordinal),
                content, "test-parser", NOW);
    }
}
