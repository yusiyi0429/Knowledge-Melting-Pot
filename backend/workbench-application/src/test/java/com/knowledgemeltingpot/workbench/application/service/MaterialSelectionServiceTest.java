package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelectionPort;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import java.time.Instant;
import java.util.List;
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
    private MaterialSelectionService service;
    private UUID roundId;
    private UUID subSceneId;

    @BeforeEach
    void setUp() {
        service = new MaterialSelectionService(port);
        roundId = UUID.randomUUID();
        subSceneId = UUID.randomUUID();
    }

    @Test
    void extractionFailsClosedIfPortEverReturnsHoldout() {
        when(port.findForExtraction(roundId, subSceneId))
                .thenReturn(List.of(selection(MaterialPartition.LABELED_HOLDOUT, false)));

        assertThatThrownBy(() -> service.forExtraction(roundId, subSceneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holdout");
    }

    @Test
    void qaFailsClosedIfPortEverReturnsHoldout() {
        when(port.findForQa(roundId, subSceneId))
                .thenReturn(List.of(selection(MaterialPartition.LABELED_HOLDOUT, false)));

        assertThatThrownBy(() -> service.forQa(roundId, subSceneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holdout");
    }

    @Test
    void alignmentRequiresNonHoldoutRegulatorySource() {
        when(port.findForAlignment(roundId, subSceneId))
                .thenReturn(List.of(selection(MaterialPartition.SOURCE, false)));

        assertThatThrownBy(() -> service.forAlignment(roundId, subSceneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-regulatory");
    }

    @Test
    void evaluationFailsClosedIfPortEverReturnsTrainingMaterial() {
        when(port.findForEvaluation(roundId, subSceneId))
                .thenReturn(List.of(selection(MaterialPartition.LABELED_TRAIN, false)));

        assertThatThrownBy(() -> service.forEvaluation(roundId, subSceneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-holdout");
    }

    @Test
    void validKnowledgeAndEvaluationSelectionsRemainSeparate() {
        MaterialSelection knowledge = selection(MaterialPartition.LABELED_TRAIN, false);
        MaterialSelection evaluation = selection(MaterialPartition.LABELED_HOLDOUT, false);
        when(port.findForExtraction(roundId, subSceneId)).thenReturn(List.of(knowledge));
        when(port.findForEvaluation(roundId, subSceneId)).thenReturn(List.of(evaluation));

        assertThat(service.forExtraction(roundId, subSceneId)).containsExactly(knowledge);
        assertThat(service.forEvaluation(roundId, subSceneId)).containsExactly(evaluation);
    }

    private MaterialSelection selection(MaterialPartition partition, boolean regulatorySource) {
        UUID materialId = UUID.randomUUID();
        Material material = new Material(materialId, "source.pdf", MaterialFormat.PDF, "application/pdf",
                "quarantine/" + materialId, "a".repeat(64), 100, MaterialStatus.READY, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), materialId, roundId, subSceneId, partition,
                MaterialShareScope.ROUND, regulatorySource, true, NOW);
        return new MaterialSelection(material, binding);
    }
}
