package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

/** Safe normalized prediction; raw provider output and reasoning are never persisted. */
public record EvaluationCaseResult(
        UUID evaluationRunId,
        UUID caseId,
        String prediction,
        EvaluationOutcome outcome,
        String errorCode,
        long latencyMillis,
        Instant createdAt) {

    public EvaluationCaseResult {
        evaluationRunId = DomainChecks.required(evaluationRunId, "evaluationRunId");
        caseId = DomainChecks.required(caseId, "caseId");
        prediction = DomainChecks.optionalText(prediction);
        outcome = DomainChecks.required(outcome, "outcome");
        errorCode = DomainChecks.optionalText(errorCode);
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (prediction.length() > 500 || errorCode.length() > 100 || latencyMillis < 0 || latencyMillis > 3_600_000) {
            throw new IllegalArgumentException("evaluation result exceeds its resource budget");
        }
        if (outcome == EvaluationOutcome.ERROR && errorCode.isBlank()) {
            throw new IllegalArgumentException("an error result requires a stable error code");
        }
    }
}
