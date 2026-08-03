package com.knowledgemeltingpot.workbench.worker;

import com.knowledgemeltingpot.workbench.application.port.JobLeaseRepository;
import com.knowledgemeltingpot.workbench.application.service.JobService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkerJobContext {
    private final UUID jobId;
    private final String workerId;
    private final Duration leaseDuration;
    private final JobLeaseRepository leases;
    private final JobService jobs;
    private final Clock clock;
    private final AtomicBoolean leaseLost = new AtomicBoolean(false);

    WorkerJobContext(UUID jobId, String workerId, Duration leaseDuration, JobLeaseRepository leases,
            JobService jobs, Clock clock) {
        this.jobId = jobId;
        this.workerId = workerId;
        this.leaseDuration = leaseDuration;
        this.leases = leases;
        this.jobs = jobs;
        this.clock = clock;
    }

    public void progress(int percentage, String stage) {
        if (leaseLost.get() || !leases.updateProgress(jobId, workerId, percentage, Instant.now(clock))) {
            leaseLost.set(true);
            return;
        }
        jobs.appendEvent(jobId, "progress", Map.of(
                "percent", percentage,
                "stage", stage,
                "messageCode", "JOB_PROGRESS",
                "traceId", "job-" + jobId));
    }

    public boolean cancellationRequested() {
        return leases.cancellationRequested(jobId);
    }

    boolean renew() {
        boolean renewed = leases.renew(jobId, workerId, leaseDuration, Instant.now(clock));
        if (!renewed) {
            leaseLost.set(true);
        }
        return renewed;
    }

    boolean leaseLost() {
        return leaseLost.get();
    }
}
