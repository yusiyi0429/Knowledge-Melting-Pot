package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.domain.AgentMountScope;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcAgentMountRepositorySqlTest {
    @Test
    void advisoryLockReturnsAMappableScalarInsteadOfPostgresVoid() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Integer> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
        when(statement.param(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(statement);
        when(statement.query(Integer.class)).thenReturn(query);
        when(query.single()).thenReturn(1);

        new JdbcAgentMountRepository(jdbc).lockScope(AgentMountScope.SCENE, UUID.randomUUID());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT 1 FROM pg_advisory_xact_lock(hashtext(:key))");
        verify(statement).query(Integer.class);
    }
}
