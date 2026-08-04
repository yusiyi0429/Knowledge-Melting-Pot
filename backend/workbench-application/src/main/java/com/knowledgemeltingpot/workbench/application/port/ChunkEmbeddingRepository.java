package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.ChunkEmbedding;
import java.util.List;

/**
 * Persistence port for chunk embeddings. Writes are idempotent (unique key
 * chunk_id + profile_version_id) and re-verify the chunk content hash and the
 * vector dimension before any row is created.
 */
public interface ChunkEmbeddingRepository {

    /**
     * Writes all embeddings; rows whose content hash does not match the
     * referenced chunk are rejected, and repeated writes for an already
     * embedded chunk are skipped. Returns the number of chunks with an
     * embedding row present after the call.
     */
    int writeAll(List<ChunkEmbedding> embeddings);
}
