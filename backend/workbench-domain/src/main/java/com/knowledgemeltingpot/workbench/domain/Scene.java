package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record Scene(
        UUID id,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    public Scene {
        id = DomainChecks.required(id, "id");
        name = DomainChecks.text(name, "name");
        description = DomainChecks.optionalText(description);
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
    }

    public Scene update(String nextName, String nextDescription, Instant now) {
        return new Scene(id, nextName, nextDescription, createdAt, now);
    }
}
