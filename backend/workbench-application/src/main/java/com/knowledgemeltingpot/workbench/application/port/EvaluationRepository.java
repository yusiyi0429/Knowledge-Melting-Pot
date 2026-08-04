package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.EvaluationCase;
import com.knowledgemeltingpot.workbench.domain.EvaluationCaseResult;
import com.knowledgemeltingpot.workbench.domain.EvaluationRun;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationRepository {
    EvaluationRun insert(EvaluationRun run);

    Optional<EvaluationRun> find(UUID runId);

    List<EvaluationRun> findRecent(UUID releaseId, UUID subSceneId, int limit);

    boolean markRunning(UUID runId, UUID jobId, Instant startedAt);

    /** Inserts the immutable case snapshot exactly once. */
    boolean insertCaseSet(UUID runId, String caseSetHash, List<EvaluationCase> cases, Instant updatedAt);

    List<EvaluationCase> findCases(UUID runId);

    List<EvaluationCaseResult> findResults(UUID runId);

    boolean insertResult(EvaluationCaseResult result);

    EvaluationCounts counts(UUID runId);

    boolean markSucceeded(UUID runId, EvaluationCounts counts, BigDecimal accuracy, Instant completedAt);

    boolean markFailed(UUID runId, String failureCode, Instant completedAt);

    boolean markCancelled(UUID runId, Instant completedAt);

    record EvaluationCounts(int total, int passed, int failed, int errors) { }
}
