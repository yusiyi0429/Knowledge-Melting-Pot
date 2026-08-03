package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record AlignmentJobCommand(
        UUID baseRevisionId,
        AlignmentAction action,
        List<UUID> regulatoryMaterialIds) {

    public AlignmentJobCommand {
        if (baseRevisionId == null) {
            throw new IllegalArgumentException("baseRevisionId is required");
        }
        if (action == null) {
            throw new IllegalArgumentException("alignment action is required");
        }
        regulatoryMaterialIds = regulatoryMaterialIds == null ? List.of() : List.copyOf(regulatoryMaterialIds);
        if (regulatoryMaterialIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(regulatoryMaterialIds).size() != regulatoryMaterialIds.size()) {
            throw new IllegalArgumentException("regulatory material IDs must be unique and non-null");
        }
    }
}
