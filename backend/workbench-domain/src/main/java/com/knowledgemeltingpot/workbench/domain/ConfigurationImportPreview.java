package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record ConfigurationImportPreview(
        UUID id,
        String schemaVersion,
        AgentMountScope scope,
        UUID scopeId,
        UUID sceneId,
        String baseEtag,
        String manifestJson,
        String manifestHash,
        String diffJson,
        UUID createdBy,
        Instant createdAt,
        UUID appliedBy,
        Instant appliedAt) {

    public boolean applied() {
        return appliedAt != null;
    }

    public boolean isApplied() {
        return applied();
    }
}
