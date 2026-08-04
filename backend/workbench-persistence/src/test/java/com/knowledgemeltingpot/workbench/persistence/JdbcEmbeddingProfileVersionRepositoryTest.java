package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcEmbeddingProfileVersionRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void insertWritesAllProfileFields() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        JdbcEmbeddingProfileVersionRepository repository = new JdbcEmbeddingProfileVersionRepository(jdbc);
        EmbeddingProfileVersion profile = new EmbeddingProfileVersion(UUID.randomUUID(), UUID.randomUUID(),
                "dashscope", "text-embedding-v4", 1024, "1", "L2", "COSINE", true, NOW);

        repository.insertAndActivate(profile, UUID.randomUUID(), NOW);

        verify(statement).param("modelConnectionId", profile.modelConnectionId());
        verify(statement).param("provider", "dashscope");
        verify(statement).param("modelId", "text-embedding-v4");
        verify(statement).param("dimension", 1024);
        verify(statement).param("profileVersion", "1");
        verify(statement).param("normalization", "L2");
        verify(statement).param("distanceFunction", "COSINE");
    }

    @Test
    void findActiveReturnsEmptyWhenNoProfileIsConfigured() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<EmbeddingProfileVersion> query = mock(JdbcClient.MappedQuerySpec.class);
        when(query.optional()).thenReturn(Optional.empty());
        when(statement.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(query);
        JdbcEmbeddingProfileVersionRepository repository = new JdbcEmbeddingProfileVersionRepository(jdbc);

        assertThat(repository.findActive()).isEmpty();
    }
}
