package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelectionPort;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialSelectionService {
    private final MaterialSelectionPort selectionPort;

    public MaterialSelectionService(MaterialSelectionPort selectionPort) {
        this.selectionPort = selectionPort;
    }

    @Transactional(readOnly = true)
    public List<MaterialSelection> forExtraction(UUID roundId, UUID subSceneId) {
        return requireKnowledgeSafe(selectionPort.findForExtraction(roundId, subSceneId), roundId, subSceneId,
                false);
    }

    @Transactional(readOnly = true)
    public List<MaterialSelection> forAlignment(UUID roundId, UUID subSceneId) {
        return requireKnowledgeSafe(selectionPort.findForAlignment(roundId, subSceneId), roundId, subSceneId,
                true);
    }

    @Transactional(readOnly = true)
    public List<MaterialSelection> forQa(UUID roundId, UUID subSceneId) {
        return requireKnowledgeSafe(selectionPort.findForQa(roundId, subSceneId), roundId, subSceneId, false);
    }

    @Transactional(readOnly = true)
    public List<MaterialSelection> forEvaluation(UUID roundId, UUID subSceneId) {
        List<MaterialSelection> selections = List.copyOf(selectionPort.findForEvaluation(roundId, subSceneId));
        selections.forEach(selection -> requireBaseSelection(selection, roundId, subSceneId));
        if (selections.stream().anyMatch(selection -> !selection.binding().partition().evaluationVisible())) {
            throw new IllegalStateException("material isolation breach: evaluation received non-holdout material");
        }
        return selections;
    }

    private List<MaterialSelection> requireKnowledgeSafe(List<MaterialSelection> candidates, UUID roundId,
            UUID subSceneId, boolean regulatoryOnly) {
        List<MaterialSelection> selections = List.copyOf(candidates);
        selections.forEach(selection -> requireBaseSelection(selection, roundId, subSceneId));
        if (selections.stream().map(selection -> selection.binding().partition())
                .anyMatch(partition -> partition == MaterialPartition.LABELED_HOLDOUT)) {
            throw new IllegalStateException("material isolation breach: knowledge workflow received holdout material");
        }
        if (regulatoryOnly && selections.stream().anyMatch(selection -> !selection.binding().regulatorySource())) {
            throw new IllegalStateException("material isolation breach: alignment received non-regulatory material");
        }
        return selections;
    }

    private void requireBaseSelection(MaterialSelection selection, UUID roundId, UUID subSceneId) {
        if (!selection.binding().active()
                || selection.material().status() != MaterialStatus.READY
                || !selection.binding().roundId().equals(roundId)
                || !selection.binding().subSceneId().equals(subSceneId)) {
            throw new IllegalStateException("material selection port returned an ineligible binding");
        }
    }
}
