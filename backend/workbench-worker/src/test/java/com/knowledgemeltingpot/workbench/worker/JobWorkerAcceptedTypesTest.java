package com.knowledgemeltingpot.workbench.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.JobLeaseRepository;
import com.knowledgemeltingpot.workbench.application.service.JobService;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobWorkerAcceptedTypesTest {
    private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        jobExecutor.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }

    @Test
    void acceptedTypesRestrictsClaimedJobTypes() {
        JobLeaseRepository leases = mock(JobLeaseRepository.class);
        JobService jobs = mock(JobService.class);
        JobHandler ingestHandler = mock(JobHandler.class);
        when(ingestHandler.supports(JobType.INGEST)).thenReturn(true);
        when(leases.claimNext(any(), eq(Set.of(JobType.INGEST)), any(), any()))
                .thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

        JobWorker worker = new JobWorker(leases, jobs, List.of(ingestHandler), jobExecutor, heartbeatExecutor,
                clock, "test-worker", Duration.ofMinutes(2), 1, "INGEST");
        worker.poll();

        verify(leases).claimNext(eq("test-worker"), eq(Set.of(JobType.INGEST)), any(), any());
        verify(leases, never()).claimNext(any(), eq(Set.of(JobType.EXTRACT)), any(), any());
    }

    @Test
    void emptyAcceptedTypesFallsBackToSupportedTypes() {
        JobLeaseRepository leases = mock(JobLeaseRepository.class);
        JobService jobs = mock(JobService.class);
        JobHandler ingestHandler = mock(JobHandler.class);
        when(ingestHandler.supports(JobType.INGEST)).thenReturn(true);
        when(leases.claimNext(any(), eq(Set.of(JobType.INGEST)), any(), any()))
                .thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

        JobWorker worker = new JobWorker(leases, jobs, List.of(ingestHandler), jobExecutor, heartbeatExecutor,
                clock, "test-worker", Duration.ofMinutes(2), 1, "");
        worker.poll();

        verify(leases).claimNext(eq("test-worker"), eq(Set.of(JobType.INGEST)), any(), any());
    }
}
