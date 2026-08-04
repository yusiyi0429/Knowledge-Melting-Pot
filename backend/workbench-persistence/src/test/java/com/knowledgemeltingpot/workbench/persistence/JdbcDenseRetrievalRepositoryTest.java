package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.domain.DenseRetrievalResult;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcDenseRetrievalRepositoryTest {
    @Test
    void queryUsesProfileIndexShapeAndPhysicallyExcludesHoldout() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<DenseRetrievalResult> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of());
        EmbeddingProfileVersion profile = new EmbeddingProfileVersion(UUID.randomUUID(), UUID.randomUUID(),
                "DASHSCOPE", "text-embedding-v4", 1024, "v1", "L2", "COSINE", true,
                Instant.parse("2026-08-04T00:00:00Z"));

        new JdbcDenseRetrievalRepository(jdbc).searchKnowledge(UUID.randomUUID(), UUID.randomUUID(), profile,
                java.util.Collections.nCopies(1024, 0.01f), 10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains("rm.partition IN ('SOURCE', 'LABELED_TRAIN')",
                "ce.vector::vector(1024) <=>", "profile_version_id = '" + profile.id() + "'::uuid")
                .doesNotContain("LABELED_HOLDOUT");
    }
}
