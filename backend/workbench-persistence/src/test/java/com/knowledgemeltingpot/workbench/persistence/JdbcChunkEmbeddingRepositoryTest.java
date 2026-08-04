package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.domain.ChunkEmbedding;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcChunkEmbeddingRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void emptyWriteSkipsAllStatements() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcChunkEmbeddingRepository repository = new JdbcChunkEmbeddingRepository(jdbc);

        int result = repository.writeAll(List.of());

        assertThat(result).isZero();
        verify(jdbc, never()).sql(anyString());
    }

    @Test
    void writePassesVectorTextContentHashAndDimension() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        JdbcClient.MappedQuerySpec<Integer> countQuery = queryMock(1);
        when(statement.query(Integer.class)).thenReturn(countQuery);
        JdbcChunkEmbeddingRepository repository = new JdbcChunkEmbeddingRepository(jdbc);
        UUID chunkId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        ChunkEmbedding embedding = new ChunkEmbedding(chunkId, profileId, "a".repeat(64), 3,
                List.of(0.1f, 0.2f, 0.3f), NOW);

        int result = repository.writeAll(List.of(embedding));

        assertThat(result).isEqualTo(1);
        verify(statement).param("chunkId", chunkId);
        verify(statement, org.mockito.Mockito.times(2)).param("profileVersionId", profileId);
        verify(statement).param("contentHash", embedding.contentHash());
        verify(statement).param("dimension", 3);
        verify(statement).param("vector", "[0.1,0.2,0.3]");
    }

    @Test
    void rejectsMixedProfileVersions() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcChunkEmbeddingRepository repository = new JdbcChunkEmbeddingRepository(jdbc);
        ChunkEmbedding first = new ChunkEmbedding(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), 1,
                List.of(0.1f), NOW);
        ChunkEmbedding second = new ChunkEmbedding(UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64), 1,
                List.of(0.2f), NOW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.writeAll(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same profile version");
        verify(jdbc, never()).sql(anyString());
    }

    private static JdbcClient.MappedQuerySpec<Integer> queryMock(Integer value) {
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Integer> query = mock(JdbcClient.MappedQuerySpec.class);
        when(query.single()).thenReturn(value);
        return query;
    }
}
