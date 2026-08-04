package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One immutable, server-parsed Holdout case. Expected output never enters the model request. */
public record EvaluationCase(
        UUID id,
        UUID evaluationRunId,
        int ordinal,
        String caseKey,
        String input,
        String expected,
        UUID materialId,
        UUID chunkId,
        String sourceRefCode,
        String contentHash,
        List<String> tags,
        Instant createdAt) {

    public EvaluationCase {
        id = DomainChecks.required(id, "id");
        evaluationRunId = DomainChecks.required(evaluationRunId, "evaluationRunId");
        caseKey = DomainChecks.text(caseKey, "caseKey");
        input = DomainChecks.text(input, "input");
        expected = DomainChecks.text(expected, "expected");
        materialId = DomainChecks.required(materialId, "materialId");
        chunkId = DomainChecks.required(chunkId, "chunkId");
        sourceRefCode = DomainChecks.text(sourceRefCode, "sourceRefCode");
        contentHash = DomainChecks.text(contentHash, "contentHash");
        tags = tags == null ? List.of() : List.copyOf(tags);
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (ordinal < 0 || caseKey.length() > 120 || input.length() > 20_000 || expected.length() > 500) {
            throw new IllegalArgumentException("evaluation case exceeds its resource budget");
        }
        if (!contentHash.matches("[0-9a-f]{64}") || tags.size() > 16
                || tags.stream().anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > 80)) {
            throw new IllegalArgumentException("evaluation case metadata is invalid");
        }
    }
}
