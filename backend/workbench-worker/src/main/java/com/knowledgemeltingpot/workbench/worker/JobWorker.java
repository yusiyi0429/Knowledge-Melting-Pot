package com.knowledgemeltingpot.workbench.worker;

import com.knowledgemeltingpot.workbench.application.port.JobLeaseRepository;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.service.JobService;
import com.knowledgemeltingpot.workbench.application.service.NotificationService;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobWorker.class);

    private final JobLeaseRepository leases;
    private final JobService jobs;
    private final NotificationService notifications;
    private final List<JobHandler> handlers;
    private final ExecutorService jobExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Clock clock;
    private final String workerId;
    private final Duration leaseDuration;
    private final Semaphore capacity;
    private final Set<JobType> acceptedTypes;

    public JobWorker(JobLeaseRepository leases, JobService jobs, NotificationService notifications,
            List<JobHandler> handlers,
            ExecutorService jobExecutor, ScheduledExecutorService heartbeatExecutor, Clock clock,
            @Value("${workbench.worker.id:${HOSTNAME:worker}-${random.uuid}}") String workerId,
            @Value("${workbench.worker.lease-duration:PT2M}") Duration leaseDuration,
            @Value("${workbench.worker.max-concurrency:4}") int maxConcurrency,
            @Value("${workbench.worker.accepted-types:}") String acceptedTypesCsv) {
        this.leases = leases;
        this.jobs = jobs;
        this.notifications = notifications;
        this.handlers = List.copyOf(handlers);
        this.jobExecutor = jobExecutor;
        this.heartbeatExecutor = heartbeatExecutor;
        this.clock = clock;
        this.workerId = workerId;
        this.leaseDuration = leaseDuration;
        this.capacity = new Semaphore(maxConcurrency);
        this.acceptedTypes = parseAcceptedTypes(acceptedTypesCsv);
    }

    @Scheduled(fixedDelayString = "${workbench.worker.poll-delay:1000}")
    public void poll() {
        if (!capacity.tryAcquire()) {
            return;
        }
        Set<JobType> claimable = acceptedTypes.isEmpty()
                ? supportedTypes()
                : EnumSet.copyOf(acceptedTypes);
        var claimed = leases.claimNext(workerId, claimable, leaseDuration, Instant.now(clock));
        if (claimed.isEmpty()) {
            capacity.release();
            return;
        }
        jobExecutor.execute(() -> {
            try {
                execute(claimed.orElseThrow());
            } finally {
                capacity.release();
            }
        });
    }

    private Set<JobType> supportedTypes() {
        Set<JobType> supported = EnumSet.noneOf(JobType.class);
        for (JobHandler handler : handlers) {
            for (JobType type : JobType.values()) {
                if (handler.supports(type)) {
                    supported.add(type);
                }
            }
        }
        return supported;
    }

    private Set<JobType> parseAcceptedTypes(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(JobType.class);
        }
        Set<JobType> result = EnumSet.noneOf(JobType.class);
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(JobType.valueOf(trimmed));
            }
        }
        return result;
    }

    private void execute(LeasedJob leasedJob) {
        JobHandler handler = handlers.stream()
                .filter(candidate -> candidate.supports(leasedJob.job().type()))
                .min(Comparator.comparingInt(JobHandler::order))
                .orElseThrow();
        WorkerJobContext context = new WorkerJobContext(leasedJob.job().id(), workerId, leaseDuration,
                leases, jobs, clock);
        long heartbeatMillis = Math.max(1_000, leaseDuration.toMillis() / 3);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(context::renew,
                heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        jobs.appendEvent(leasedJob.job().id(), "started",
                Map.of("attempt", leasedJob.attempt(), "worker", workerId,
                        "stage", "STARTING", "percent", 1,
                        "messageCode", "JOB_STARTED", "traceId", jobTraceId(leasedJob)));
        try {
            JobHandlingResult result = handler.handle(leasedJob, context);
            if (context.leaseLost() || context.cancellationRequested()) {
                return;
            }
            if (result.succeeded()) {
                if (leases.succeed(leasedJob.job().id(), workerId, result.resultReference(), Instant.now(clock))) {
                    jobs.appendEvent(leasedJob.job().id(), "completed",
                            Map.of("status", "SUCCEEDED", "resultReference", result.resultReference(),
                                    "stage", "COMPLETED", "percent", 100,
                                    "messageCode", "JOB_COMPLETED", "traceId", jobTraceId(leasedJob)));
                    safeNotify(leasedJob.job().id());
                }
            } else if (leases.fail(leasedJob.job().id(), workerId, result.errorCode(),
                    safeMessage(result.errorMessage()), Instant.now(clock))) {
                jobs.appendEvent(leasedJob.job().id(), "failed",
                        Map.of("status", "FAILED", "errorCode", result.errorCode(),
                                "stage", "FAILED", "percent", jobs.get(leasedJob.job().id()).progress(),
                                "messageCode", result.errorCode(), "traceId", jobTraceId(leasedJob)));
                safeNotify(leasedJob.job().id());
            }
        } catch (Exception exception) {
            LOGGER.error("Worker job {} failed with {}", leasedJob.job().id(), exception.getClass().getSimpleName());
            if (!context.leaseLost() && leases.fail(leasedJob.job().id(), workerId, "WORKER_ERROR",
                    "Job execution failed", Instant.now(clock))) {
                jobs.appendEvent(leasedJob.job().id(), "failed",
                        Map.of("status", "FAILED", "errorCode", "WORKER_ERROR",
                                "stage", "FAILED", "percent", jobs.get(leasedJob.job().id()).progress(),
                                "messageCode", "WORKER_ERROR", "traceId", jobTraceId(leasedJob)));
                safeNotify(leasedJob.job().id());
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private String safeMessage(String value) {
        if (value == null || value.isBlank()) {
            return "Job execution failed";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private String jobTraceId(LeasedJob leasedJob) {
        return "job-" + leasedJob.job().id();
    }

    private void safeNotify(java.util.UUID jobId) {
        try {
            notifications.notifyTerminalJob(jobId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Job {} completed but notification creation failed", jobId);
        }
    }
}
