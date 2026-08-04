package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.AlignmentProposal;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.util.List;
import java.util.UUID;

public record AlignmentProposalView(
        AlignmentProposal proposal,
        KnowledgePatch patch,
        List<KnowledgeIr.SourceRef> sourceRefs,
        List<UUID> regulatoryMaterialIds) {
    public AlignmentProposalView {
        sourceRefs = List.copyOf(sourceRefs);
        regulatoryMaterialIds = List.copyOf(regulatoryMaterialIds);
    }
}
