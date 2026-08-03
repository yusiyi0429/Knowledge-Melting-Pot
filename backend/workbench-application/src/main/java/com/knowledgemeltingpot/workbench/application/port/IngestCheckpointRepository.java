package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.IngestStage;
import com.knowledgemeltingpot.workbench.domain.MaterialIngestAttempt;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for material ingest attempts and crash-recovery checkpoints.
 */
public interface IngestCheckpointRepository {

    MaterialIngestAttempt startAttempt(MaterialIngestAttempt attempt);

    /**
     * Reuse the existing attempt row of this job for a re-claim or a manual retry:
     * the attempt number is synchronized with the job's current attempt and the
     * row is reset to a fresh STARTED state (failure/completion metadata cleared).
     * Idempotent: repeated calls only overwrite the same row.
     */
    void reopenAttempt(UUID jobId, int attempt, Instant startedAt);

    void updateStage(UUID jobId, IngestStage stage);

    void completeAttempt(UUID jobId, IngestStage stage, String parserName, String parserVersion, Instant completedAt);

    void failAttempt(UUID jobId, IngestStage stage, String failureCode, boolean retryable, Instant completedAt);

    Optional<MaterialIngestAttempt> findLatestAttempt(UUID materialId);

    Optional<MaterialIngestAttempt> findByJobId(UUID jobId);
}
