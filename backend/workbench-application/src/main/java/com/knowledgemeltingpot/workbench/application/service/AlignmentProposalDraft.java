package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import java.util.List;
import java.util.UUID;

public record AlignmentProposalDraft(
        UUID documentId,
        UUID baseRevisionId,
        AlignmentAction action,
        String structuredPatchJson,
        String reason,
        String sourceRefsJson,
        List<UUID> regulatoryMaterialIds) {

    public AlignmentProposalDraft {
        regulatoryMaterialIds = regulatoryMaterialIds == null ? List.of() : List.copyOf(regulatoryMaterialIds);
    }
}
