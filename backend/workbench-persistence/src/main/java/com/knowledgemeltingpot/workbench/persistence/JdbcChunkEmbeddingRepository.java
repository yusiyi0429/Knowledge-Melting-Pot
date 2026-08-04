package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.ChunkEmbeddingRepository;
import com.knowledgemeltingpot.workbench.domain.ChunkEmbedding;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcChunkEmbeddingRepository implements ChunkEmbeddingRepository {

    private final JdbcClient jdbc;

    public JdbcChunkEmbeddingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public int writeAll(List<ChunkEmbedding> embeddings) {
        if (embeddings.isEmpty()) {
            return 0;
        }
        UUID profileVersionId = embeddings.getFirst().profileVersionId();
        if (embeddings.stream().anyMatch(embedding -> !profileVersionId.equals(embedding.profileVersionId()))) {
            throw new IllegalArgumentException("all embeddings must use the same profile version");
        }
        List<UUID> chunkIds = embeddings.stream().map(ChunkEmbedding::chunkId).toList();
        for (ChunkEmbedding embedding : embeddings) {
            jdbc.sql("""
                    INSERT INTO chunk_embedding (
                        chunk_id, profile_version_id, content_hash, dimension, vector, created_at)
                    SELECT c.id, :profileVersionId, c.content_hash, :dimension, CAST(:vector AS vector), :createdAt
                    FROM material_chunk c
                    WHERE c.id = :chunkId AND c.content_hash = :contentHash
                    ON CONFLICT (chunk_id, profile_version_id) DO NOTHING
                    """)
                    .param("chunkId", embedding.chunkId())
                    .param("profileVersionId", embedding.profileVersionId())
                    .param("contentHash", embedding.contentHash())
                    .param("dimension", embedding.dimension())
                    .param("vector", vectorText(embedding.vector()))
                    .param("createdAt", JdbcTimes.toJdbc(embedding.createdAt()))
                    .update();
        }
        Integer present = jdbc.sql("""
                SELECT COUNT(*) FROM chunk_embedding
                WHERE chunk_id IN (:chunkIds) AND profile_version_id = :profileVersionId
                """)
                .param("chunkIds", chunkIds)
                .param("profileVersionId", profileVersionId)
                .query(Integer.class)
                .single();
        return present == null ? 0 : present;
    }

    private String vectorText(List<Float> vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (Float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }
}
