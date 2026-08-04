package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.ChunkEmbedding;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import java.util.List;

/**
 * Port for a real embedding provider. An implementation exists only when a
 * real model configuration is available; the worker must never fall back to
 * random, hash-derived or fixed fake vectors. Tests may inject a deterministic
 * fake implementation.
 */
public interface EmbeddingPort {

    String provider();

    /**
     * Computes one vector per chunk under the given profile. The returned
     * vectors must match the profile dimension; implementations must not log
     * or persist chunk content.
     */
    List<ChunkEmbedding> embed(List<MaterialChunk> chunks, EmbeddingProfileVersion profile);

    /** Computes the query-side vector using the same immutable profile. */
    List<Float> embedQuery(String query, EmbeddingProfileVersion profile);
}
