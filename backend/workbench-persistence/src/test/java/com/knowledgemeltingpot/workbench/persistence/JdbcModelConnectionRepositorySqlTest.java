package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcModelConnectionRepositorySqlTest {
    @Test
    void separatesReturningKeywordFromVersionColumns() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ModelConfigVersion> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        ModelConfigVersion expected = new ModelConfigVersion(UUID.randomUUID(), UUID.randomUUID(), 1,
                "fixture-model", BigDecimal.ZERO, 2048, UUID.randomUUID(), Instant.parse("2026-08-04T00:00:00Z"));
        when(query.optional()).thenReturn(Optional.of(expected));
        JdbcModelConnectionRepository repository = new JdbcModelConnectionRepository(jdbc);

        ModelConfigVersion saved = repository.appendConfigVersion(expected.id(), expected.modelConnectionId(),
                expected.modelId(), expected.temperature(), expected.maxOutputTokens(), expected.createdBy(),
                expected.createdAt());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).containsPattern("RETURNING\\s+id, model_connection_id");
        assertThat(sql.getValue()).doesNotContain("RETURNINGid");
        assertThat(saved).isEqualTo(expected);
    }
}
