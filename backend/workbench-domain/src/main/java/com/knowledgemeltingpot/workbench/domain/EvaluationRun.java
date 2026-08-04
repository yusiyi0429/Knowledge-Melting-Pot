package com.knowledgemeltingpot.workbench.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable evaluation inputs plus mutable, aggregate execution state. */
public record EvaluationRun(
        UUID id,
        UUID releaseId,
        UUID subSceneId,
        UUID roundId,
        UUID documentRevisionId,
        UUID evaluationAssetId,
        UUID skillAssetId,
        UUID modelConfigVersionId,
        UUID skillVersionId,
        UUID jobId,
        String caseSetHash,
        EvaluationStatus status,
        int totalCases,
        int passedCases,
        int failedCases,
        int errorCases,
        BigDecimal accuracy,
        String failureCode,
        UUID createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {

    public EvaluationRun {
        id = DomainChecks.required(id, "id");
        releaseId = DomainChecks.required(releaseId, "releaseId");
        subSceneId = DomainChecks.required(subSceneId, "subSceneId");
        roundId = DomainChecks.required(roundId, "roundId");
        documentRevisionId = DomainChecks.required(documentRevisionId, "documentRevisionId");
        evaluationAssetId = DomainChecks.required(evaluationAssetId, "evaluationAssetId");
        skillAssetId = DomainChecks.required(skillAssetId, "skillAssetId");
        modelConfigVersionId = DomainChecks.required(modelConfigVersionId, "modelConfigVersionId");
        skillVersionId = DomainChecks.required(skillVersionId, "skillVersionId");
        jobId = DomainChecks.required(jobId, "jobId");
        caseSetHash = DomainChecks.optionalText(caseSetHash);
        status = DomainChecks.required(status, "status");
        failureCode = DomainChecks.optionalText(failureCode);
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
        if (totalCases < 0 || passedCases < 0 || failedCases < 0 || errorCases < 0
                || passedCases + failedCases + errorCases > totalCases) {
            throw new IllegalArgumentException("evaluation counts are invalid");
        }
        if (!caseSetHash.isBlank() && !caseSetHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("caseSetHash must be a lowercase SHA-256 digest");
        }
        if (accuracy != null && (accuracy.compareTo(BigDecimal.ZERO) < 0
                || accuracy.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("accuracy must be between zero and one");
        }
        if (status == EvaluationStatus.SUCCEEDED && (completedAt == null || accuracy == null || totalCases == 0)) {
            throw new IllegalArgumentException("a successful evaluation requires cases, accuracy and completion time");
        }
    }
}
