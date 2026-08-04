package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record AgentMountVersion(
        UUID id,
        AgentRole role,
        AgentMountScope scope,
        UUID scopeId,
        int version,
        UUID templateVersionId,
        Boolean enabled,
        UUID modelConfigVersionId,
        UUID skillVersionId,
        String optionsJson,
        String configHash,
        UUID createdBy,
        Instant createdAt) {

    public AgentMountVersion {
        id = DomainChecks.required(id, "id");
        role = DomainChecks.required(role, "role");
        scope = DomainChecks.required(scope, "scope");
        if (scope == AgentMountScope.GLOBAL && scopeId != null) {
            throw new IllegalArgumentException("GLOBAL Agent mount must not have a scopeId");
        }
        if (scope != AgentMountScope.GLOBAL && scopeId == null) {
            throw new IllegalArgumentException(scope + " Agent mount requires a scopeId");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        configHash = DomainChecks.text(configHash, "configHash");
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
    }
}
