package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, versioned description of a real embedding model configuration.
 * A profile row must exist before any vector is written; the worker never
 * fabricates vectors from random, hashes or fixed values.
 */
public record EmbeddingProfileVersion(
        UUID id,
        UUID modelConnectionId,
        String provider,
        String modelId,
        int dimension,
        String profileVersion,
        String normalization,
        String distanceFunction,
        boolean active,
        Instant createdAt) {

    public EmbeddingProfileVersion {
        id = DomainChecks.required(id, "id");
        modelConnectionId = DomainChecks.required(modelConnectionId, "modelConnectionId");
        provider = DomainChecks.text(provider, "provider");
        modelId = DomainChecks.text(modelId, "modelId");
        profileVersion = DomainChecks.text(profileVersion, "profileVersion");
        normalization = DomainChecks.text(normalization, "normalization");
        distanceFunction = DomainChecks.text(distanceFunction, "distanceFunction");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (dimension < 1 || dimension > 8192) {
            throw new IllegalArgumentException("dimension must be between 1 and 8192");
        }
        if (!"NONE".equals(normalization) && !"L2".equals(normalization)) {
            throw new IllegalArgumentException("normalization must be NONE or L2");
        }
        if (!"COSINE".equals(distanceFunction) && !"L2".equals(distanceFunction)) {
            throw new IllegalArgumentException("distanceFunction must be COSINE or L2");
        }
    }
}
