package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One computed vector for one chunk under one embedding profile version.
 * The vector is produced by a real embedding provider only; the content hash
 * of the chunk is carried along so the write path can re-verify it.
 */
public record ChunkEmbedding(
        UUID chunkId,
        UUID profileVersionId,
        String contentHash,
        int dimension,
        List<Float> vector,
        Instant createdAt) {

    public ChunkEmbedding {
        chunkId = DomainChecks.required(chunkId, "chunkId");
        profileVersionId = DomainChecks.required(profileVersionId, "profileVersionId");
        contentHash = DomainChecks.text(contentHash, "contentHash");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (dimension < 1 || dimension > 8192) {
            throw new IllegalArgumentException("dimension must be between 1 and 8192");
        }
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        if (vector.size() != dimension) {
            throw new IllegalArgumentException("vector dimension does not match the profile dimension");
        }
        if (!contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a lowercase 64-character hexadecimal digest");
        }
    }
}
