package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record ExplorationSession(
        UUID id,
        String title,
        ExplorationStatus status,
        UUID exploreJobId,
        UUID modelConfigVersionId,
        UUID skillVersionId,
        UUID roleConfigVersionId,
        String effectiveConfigHash,
        int version,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public ExplorationSession {
        id = DomainChecks.required(id, "id");
        title = DomainChecks.text(title, "title");
        status = DomainChecks.required(status, "status");
        effectiveConfigHash = DomainChecks.optionalText(effectiveConfigHash);
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
