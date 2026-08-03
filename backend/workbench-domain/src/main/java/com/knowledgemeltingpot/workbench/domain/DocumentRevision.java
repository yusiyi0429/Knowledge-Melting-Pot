package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record DocumentRevision(
        UUID id,
        UUID documentId,
        UUID subSceneId,
        long revision,
        UUID baseRevisionId,
        String content,
        String contentHash,
        String revisionNote,
        boolean finalized,
        UUID finalizedBy,
        Instant finalizedAt,
        UUID createdBy,
        Instant createdAt) {

    public DocumentRevision {
        id = DomainChecks.required(id, "id");
        documentId = DomainChecks.required(documentId, "documentId");
        subSceneId = DomainChecks.required(subSceneId, "subSceneId");
        content = DomainChecks.required(content, "content");
        contentHash = DomainChecks.text(contentHash, "contentHash");
        revisionNote = DomainChecks.optionalText(revisionNote);
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (finalized != (finalizedBy != null && finalizedAt != null)) {
            throw new IllegalArgumentException("finalized revision requires finalizedBy and finalizedAt");
        }
    }

    public String etag() {
        return "\"rev-" + revision + "-" + contentHash.substring(0, Math.min(12, contentHash.length())) + "\"";
    }
}
