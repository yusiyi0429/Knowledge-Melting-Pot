package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {
    Job insert(Job job);

    Optional<Job> find(UUID id);

    long appendEvent(UUID jobId, String eventType, String payloadJson, Instant occurredAt);

    List<JobEvent> findEventsAfter(UUID jobId, long afterSequence, int limit);

    boolean requestCancellation(UUID jobId, Instant now);

    boolean retry(UUID jobId, Instant now);
}
