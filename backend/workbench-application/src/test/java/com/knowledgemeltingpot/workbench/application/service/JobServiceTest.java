package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRecord;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRepository;
import com.knowledgemeltingpot.workbench.application.port.JobRepository;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Mock
    private JobRepository jobs;
    @Mock
    private IdempotencyRepository idempotency;
    @Mock
    private AuditService audit;

    private JobService service;

    @BeforeEach
    void setUp() {
        service = new JobService(jobs, idempotency, audit, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rejectsOutOfContractIdempotencyKeyBeforePersisting() {
        assertThatThrownBy(() -> service.submit(JobType.EXTRACT, "SUB_SCENE", UUID.randomUUID(), Map.of(),
                UUID.randomUUID(), "short", "trace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 8 and 128");

        verify(jobs, never()).insert(any());
    }

    @Test
    void repeatedCancelWithTheSameKeyReplaysTheFirstCommand() {
        UUID jobId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String key = "cancel-request-0001";
        Job queued = job(jobId, actorId, JobStatus.QUEUED);
        Job cancelled = job(jobId, actorId, JobStatus.CANCELLED);
        when(idempotency.find(actorId.toString(), key)).thenReturn(Optional.empty());
        when(idempotency.tryReserve(any())).thenReturn(true);
        when(jobs.find(jobId)).thenReturn(Optional.of(queued), Optional.of(cancelled));
        when(jobs.requestCancellation(jobId, NOW)).thenReturn(true);

        JobSubmission first = service.cancel(jobId, actorId, key, "trace-1");

        assertThat(first.replayed()).isFalse();
        assertThat(first.job().status()).isEqualTo(JobStatus.CANCELLED);

        IdempotencyRecord stored = new IdempotencyRecord(actorId.toString(), key,
                Hashes.sha256("CANCEL\n" + jobId), "JOB_COMMAND", jobId, NOW, NOW.plusSeconds(3600));
        when(idempotency.find(actorId.toString(), key)).thenReturn(Optional.of(stored));
        when(jobs.find(jobId)).thenReturn(Optional.of(cancelled));

        JobSubmission replay = service.cancel(jobId, actorId, key, "trace-2");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.job().status()).isEqualTo(JobStatus.CANCELLED);
        verify(jobs).requestCancellation(jobId, NOW);
    }

    private Job job(UUID id, UUID actorId, JobStatus status) {
        return new Job(id, JobType.EXTRACT, "SUB_SCENE", UUID.randomUUID(), status,
                status == JobStatus.CANCELLED ? 20 : 0, 0, "{}", "", "", "", actorId, NOW, NOW);
    }
}
