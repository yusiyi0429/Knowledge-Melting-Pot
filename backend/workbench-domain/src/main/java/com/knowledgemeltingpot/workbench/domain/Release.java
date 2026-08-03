package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record Release(
        UUID id,
        UUID sceneId,
        String tag,
        ReleaseStatus status,
        ReleaseCoverage coverage,
        String note,
        UUID previousReleaseId,
        String manifestJson,
        String manifestHash,
        UUID createdBy,
        Instant createdAt,
        Instant publishedAt) {

    public Release {
        id = DomainChecks.required(id, "id");
        sceneId = DomainChecks.required(sceneId, "sceneId");
        tag = DomainChecks.text(tag, "tag");
        status = DomainChecks.required(status, "status");
        coverage = DomainChecks.required(coverage, "coverage");
        note = DomainChecks.text(note, "note");
        manifestJson = DomainChecks.optionalText(manifestJson);
        manifestHash = DomainChecks.optionalText(manifestHash);
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (status == ReleaseStatus.PUBLISHED && (manifestJson.isBlank() || manifestHash.isBlank() || publishedAt == null)) {
            throw new IllegalArgumentException("published release requires manifest and publishedAt");
        }
    }

    public boolean partial() {
        return coverage == ReleaseCoverage.PARTIAL;
    }
}
