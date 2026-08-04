package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record SkillVersion(
        UUID id,
        UUID skillId,
        int version,
        String manifestJson,
        String packageHash,
        UUID createdBy,
        Instant createdAt) {

    public SkillVersion {
        id = DomainChecks.required(id, "id");
        skillId = DomainChecks.required(skillId, "skillId");
        manifestJson = DomainChecks.text(manifestJson, "manifestJson");
        packageHash = DomainChecks.text(packageHash, "packageHash");
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (!packageHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("packageHash must be a lowercase 64-character hexadecimal digest");
        }
    }
}
