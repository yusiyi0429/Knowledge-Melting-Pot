package com.knowledgemeltingpot.workbench.domain;

import java.util.UUID;

/** A trusted non-holdout chunk ranked by the active dense embedding profile. */
public record DenseRetrievalResult(
        UUID chunkId,
        UUID materialId,
        String sourceRefCode,
        ChunkLocator locator,
        String content,
        double score) {

    public DenseRetrievalResult {
        chunkId = DomainChecks.required(chunkId, "chunkId");
        materialId = DomainChecks.required(materialId, "materialId");
        sourceRefCode = DomainChecks.text(sourceRefCode, "sourceRefCode");
        locator = DomainChecks.required(locator, "locator");
        content = DomainChecks.text(content, "content");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
    }
}
