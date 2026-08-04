package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record AgentRoleTemplateVersion(
        UUID id,
        AgentRole role,
        int version,
        String displayName,
        String description,
        String defaultOptionsJson,
        String configHash,
        Instant createdAt) {
}
