package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

/** Redacted execution provenance. Prompt, source text and raw model output are intentionally absent. */
public record AgentExecutionAttempt(UUID id, UUID jobId, int jobAttempt, AgentRole role, AssetType assetType,
        UUID assetId,
        UUID modelConfigVersionId, UUID skillVersionId, UUID roleConfigVersionId, String effectiveConfigHash,
        String inputHash, String outputHash, AgentExecutionAttemptStatus status, String failureCode,
        Instant startedAt, Instant completedAt) {
    public AgentExecutionAttempt {
        id = DomainChecks.required(id, "id");
        jobId = DomainChecks.required(jobId, "jobId");
        role = DomainChecks.required(role, "role");
        assetType = DomainChecks.required(assetType, "assetType");
        assetId = DomainChecks.required(assetId, "assetId");
        modelConfigVersionId = DomainChecks.required(modelConfigVersionId, "modelConfigVersionId");
        skillVersionId = DomainChecks.required(skillVersionId, "skillVersionId");
        effectiveConfigHash = DomainChecks.text(effectiveConfigHash, "effectiveConfigHash");
        inputHash = DomainChecks.text(inputHash, "inputHash");
        outputHash = DomainChecks.optionalText(outputHash);
        status = DomainChecks.required(status, "status");
        failureCode = DomainChecks.optionalText(failureCode);
        startedAt = DomainChecks.required(startedAt, "startedAt");
        if (jobAttempt < 1) throw new IllegalArgumentException("jobAttempt must be positive");
        if (status == AgentExecutionAttemptStatus.RUNNING && completedAt != null) {
            throw new IllegalArgumentException("running attempt must not be completed");
        }
        if (status != AgentExecutionAttemptStatus.RUNNING && completedAt == null) {
            throw new IllegalArgumentException("terminal attempt requires completedAt");
        }
    }
}
