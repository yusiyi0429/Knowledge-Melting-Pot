package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record RoundMaterial(
        UUID id,
        UUID materialId,
        UUID roundId,
        UUID subSceneId,
        MaterialPartition partition,
        MaterialShareScope shareScope,
        boolean regulatorySource,
        boolean active,
        Instant createdAt) {

    public RoundMaterial {
        id = DomainChecks.required(id, "id");
        materialId = DomainChecks.required(materialId, "materialId");
        roundId = DomainChecks.required(roundId, "roundId");
        subSceneId = DomainChecks.required(subSceneId, "subSceneId");
        partition = DomainChecks.required(partition, "partition");
        shareScope = DomainChecks.required(shareScope, "shareScope");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (regulatorySource && partition == MaterialPartition.LABELED_HOLDOUT) {
            throw new IllegalArgumentException("holdout material cannot be marked as a regulatory alignment source");
        }
    }
}
