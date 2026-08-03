package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record SubScene(
        UUID id,
        UUID sceneId,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    public SubScene {
        id = DomainChecks.required(id, "id");
        sceneId = DomainChecks.required(sceneId, "sceneId");
        name = DomainChecks.text(name, "name");
        description = DomainChecks.optionalText(description);
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
    }
}
