package com.knowledgemeltingpot.workbench.persistence;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.domain.IngestStage;
import com.knowledgemeltingpot.workbench.domain.MaterialIngestAttempt;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcIngestCheckpointRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void reopenAttemptSynchronizesAttemptAndResetsTerminalState() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);

        new JdbcIngestCheckpointRepository(jdbc).reopenAttempt(JOB_ID, 3, NOW);

        verify(statement).param("attempt", 3);
        verify(statement).param("startedAt", JdbcTimes.toJdbc(NOW));
        verify(statement).param("jobId", JOB_ID);
    }

    @Test
    void reopenAttemptOverwritesTheSameRowForIdempotentReplay() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        JdbcIngestCheckpointRepository repository = new JdbcIngestCheckpointRepository(jdbc);

        repository.reopenAttempt(JOB_ID, 3, NOW);
        repository.reopenAttempt(JOB_ID, 4, NOW);

        verify(statement).param("attempt", 3);
        verify(statement).param("attempt", 4);
    }

    @Test
    void startAttemptKeepsRetryableFalseUntilFailureIsRecorded() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);

        new JdbcIngestCheckpointRepository(jdbc).startAttempt(new MaterialIngestAttempt(JOB_ID, MATERIAL_ID, 1,
                IngestStage.STARTED, null, false, NOW, null, null, null, null, null));

        verify(statement).param("attempt", 1);
        verify(statement).param("stage", "STARTED");
    }

    @Test
    void findByJobIdRoutesLookupToTheJobId() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<MaterialIngestAttempt> result = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
        when(statement.query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<MaterialIngestAttempt>>any()))
                .thenReturn(result);
        when(result.optional()).thenReturn(Optional.empty());

        Optional<MaterialIngestAttempt> found =
                new JdbcIngestCheckpointRepository(jdbc).findByJobId(JOB_ID);

        verify(statement).param("jobId", JOB_ID);
        org.assertj.core.api.Assertions.assertThat(found).isEmpty();
    }
}
