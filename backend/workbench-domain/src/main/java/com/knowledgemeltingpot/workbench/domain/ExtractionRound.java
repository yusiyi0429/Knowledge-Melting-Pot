package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record ExtractionRound(
        UUID id,
        UUID subSceneId,
        int roundNumber,
        ExtractionRoundStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public ExtractionRound {
        id = DomainChecks.required(id, "id");
        subSceneId = DomainChecks.required(subSceneId, "subSceneId");
        status = DomainChecks.required(status, "status");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
        if (roundNumber < 1) {
            throw new IllegalArgumentException("roundNumber must be positive");
        }
    }

    public ExtractionRound transitionTo(ExtractionRoundStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("invalid extraction round transition: " + status + " -> " + target);
        }
        return new ExtractionRound(id, subSceneId, roundNumber, target, createdAt, now);
    }
}
