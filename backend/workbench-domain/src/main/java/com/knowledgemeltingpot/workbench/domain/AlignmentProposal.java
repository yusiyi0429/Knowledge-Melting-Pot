package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record AlignmentProposal(
        UUID id,
        UUID documentId,
        UUID baseRevisionId,
        String baseEtag,
        AlignmentAction action,
        AlignmentProposalStatus status,
        String structuredPatchJson,
        String reason,
        String sourceRefsJson,
        String regulatoryMaterialIdsJson,
        UUID createdBy,
        Instant createdAt,
        UUID adoptedRevisionId,
        UUID adoptedBy,
        Instant adoptedAt) {

    public AlignmentProposal {
        id = DomainChecks.required(id, "id");
        documentId = DomainChecks.required(documentId, "documentId");
        baseRevisionId = DomainChecks.required(baseRevisionId, "baseRevisionId");
        baseEtag = DomainChecks.text(baseEtag, "baseEtag");
        action = DomainChecks.required(action, "action");
        status = DomainChecks.required(status, "status");
        structuredPatchJson = DomainChecks.text(structuredPatchJson, "structuredPatchJson");
        reason = DomainChecks.text(reason, "reason");
        sourceRefsJson = DomainChecks.text(sourceRefsJson, "sourceRefsJson");
        regulatoryMaterialIdsJson = DomainChecks.text(regulatoryMaterialIdsJson, "regulatoryMaterialIdsJson");
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        boolean hasAdoption = adoptedRevisionId != null && adoptedBy != null && adoptedAt != null;
        if ((status == AlignmentProposalStatus.ADOPTED) != hasAdoption) {
            throw new IllegalArgumentException("ADOPTED proposal requires immutable adoption metadata");
        }
        if (status == AlignmentProposalStatus.READY
                && (adoptedRevisionId != null || adoptedBy != null || adoptedAt != null)) {
            throw new IllegalArgumentException("READY proposal cannot contain adoption metadata");
        }
    }
}
