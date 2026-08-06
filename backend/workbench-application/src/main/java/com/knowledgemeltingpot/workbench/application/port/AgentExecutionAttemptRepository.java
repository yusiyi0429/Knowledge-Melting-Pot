package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.AgentExecutionAttempt;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AgentExecutionAttemptRepository {
    AgentExecutionAttempt insert(AgentExecutionAttempt attempt);

    boolean markSucceeded(UUID attemptId, String outputHash, Instant completedAt);

    boolean markFailed(UUID attemptId, String failureCode, Instant completedAt);

    List<AgentExecutionAttempt> findByJob(UUID jobId);

    java.util.Optional<AgentExecutionAttempt> findByAsset(UUID assetId);
}
