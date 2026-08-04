package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record ExtractionRun(
        UUID id,
        UUID jobId,
        UUID documentId,
        UUID subSceneId,
        UUID roundId,
        UUID baseRevisionId,
        String baseEtag,
        UUID modelConfigVersionId,
        UUID skillVersionId,
        UUID roleConfigVersionId,
        String roleConfigHash,
        String canonicalInputHash,
        Stage stage,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public ExtractionRun {
        id = DomainChecks.required(id, "id");
        jobId = DomainChecks.required(jobId, "jobId");
        documentId = DomainChecks.required(documentId, "documentId");
        subSceneId = DomainChecks.required(subSceneId, "subSceneId");
        roundId = DomainChecks.required(roundId, "roundId");
        modelConfigVersionId = DomainChecks.required(modelConfigVersionId, "modelConfigVersionId");
        skillVersionId = DomainChecks.required(skillVersionId, "skillVersionId");
        if ((roleConfigVersionId == null) != (roleConfigHash == null)) {
            throw new IllegalArgumentException("role configuration version and hash must both be present or absent");
        }
        canonicalInputHash = DomainChecks.text(canonicalInputHash, "canonicalInputHash");
        stage = DomainChecks.required(stage, "stage");
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
        if ((baseRevisionId == null) != (baseEtag == null)) {
            throw new IllegalArgumentException("base revision and ETag must both be present or absent");
        }
    }

    public enum Stage {
        FROZEN,
        MAPPING,
        REDUCING,
        PERSISTING,
        SUCCEEDED,
        FAILED
    }
}
