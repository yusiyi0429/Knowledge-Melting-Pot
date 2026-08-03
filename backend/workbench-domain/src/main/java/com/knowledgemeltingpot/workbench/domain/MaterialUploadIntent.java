package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record MaterialUploadIntent(
        UUID id,
        UUID materialId,
        UUID createdBy,
        UUID validationJobId,
        String clientEtag,
        Instant createdAt,
        Instant completedAt) {

    public MaterialUploadIntent {
        id = DomainChecks.required(id, "id");
        materialId = DomainChecks.required(materialId, "materialId");
        createdBy = DomainChecks.required(createdBy, "createdBy");
        clientEtag = DomainChecks.optionalText(clientEtag);
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if ((validationJobId == null) != (completedAt == null)) {
            throw new IllegalArgumentException("validationJobId and completedAt must be set together");
        }
    }
}
