package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.util.List;
import java.util.UUID;

public record AlignmentProposalDraft(
        UUID documentId,
        UUID baseRevisionId,
        AlignmentAction action,
        KnowledgeIr replacement,
        String reason,
        List<UUID> regulatoryMaterialIds) {

    public AlignmentProposalDraft {
        regulatoryMaterialIds = regulatoryMaterialIds == null ? List.of() : List.copyOf(regulatoryMaterialIds);
    }
}
