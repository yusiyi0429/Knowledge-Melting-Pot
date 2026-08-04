package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record Skill(
        UUID id,
        String name,
        SkillKind kind,
        String description,
        UUID sceneId,
        UUID sourceSkillId,
        UUID sourceSkillVersionId,
        UUID createdBy,
        Instant createdAt) {

    public Skill {
        id = DomainChecks.required(id, "id");
        name = DomainChecks.text(name, "name");
        kind = DomainChecks.required(kind, "kind");
        description = DomainChecks.optionalText(description);
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (name.length() > 200) {
            throw new IllegalArgumentException("name must not exceed 200 characters");
        }
        if (description.length() > 2000) {
            throw new IllegalArgumentException("description must not exceed 2000 characters");
        }
        if (kind == SkillKind.TEMPLATE && sceneId != null) {
            throw new IllegalArgumentException("a template skill cannot be bound to a scene");
        }
        if (kind == SkillKind.INSTANCE && sceneId == null) {
            throw new IllegalArgumentException("an instance skill requires a scene");
        }
        if (kind == SkillKind.INSTANCE && (sourceSkillId == null || sourceSkillVersionId == null)) {
            throw new IllegalArgumentException(
                    "an instance skill requires both a source skill and source skill version");
        }
        if (kind == SkillKind.TEMPLATE && (sourceSkillId != null || sourceSkillVersionId != null)) {
            throw new IllegalArgumentException("a template skill cannot have a fork source");
        }
    }

    public Skill fork(UUID id, UUID sceneId, UUID sourceSkillVersionId, UUID forkedBy, Instant now) {
        return new Skill(id, name, SkillKind.INSTANCE, description, sceneId, this.id,
                sourceSkillVersionId, forkedBy, now);
    }
}
