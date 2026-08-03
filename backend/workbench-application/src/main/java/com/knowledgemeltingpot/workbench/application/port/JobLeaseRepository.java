package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface JobLeaseRepository {
    Optional<LeasedJob> claimNext(String workerId, Set<JobType> acceptedTypes, Duration leaseDuration, Instant now);

    boolean renew(UUID jobId, String workerId, Duration leaseDuration, Instant now);

    boolean updateProgress(UUID jobId, String workerId, int progress, Instant now);

    boolean succeed(UUID jobId, String workerId, String resultReference, Instant now);

    boolean fail(UUID jobId, String workerId, String errorCode, String errorMessage, Instant now);

    boolean cancellationRequested(UUID jobId);
}
