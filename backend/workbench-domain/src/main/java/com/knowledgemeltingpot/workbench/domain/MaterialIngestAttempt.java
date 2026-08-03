package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record MaterialIngestAttempt(
        UUID jobId,
        UUID materialId,
        int attempt,
        IngestStage stage,
        String failureCode,
        boolean retryable,
        Instant startedAt,
        Instant completedAt,
        String scanEngineVersion,
        String scanSignatureVersion,
        String parserName,
        String parserVersion) {

    public MaterialIngestAttempt {
        jobId = DomainChecks.required(jobId, "jobId");
        materialId = DomainChecks.required(materialId, "materialId");
        attempt = DomainChecks.positive(attempt, "attempt");
        stage = DomainChecks.required(stage, "stage");
        failureCode = DomainChecks.optionalText(failureCode);
        startedAt = DomainChecks.required(startedAt, "startedAt");
        scanEngineVersion = DomainChecks.optionalText(scanEngineVersion);
        scanSignatureVersion = DomainChecks.optionalText(scanSignatureVersion);
        parserName = DomainChecks.optionalText(parserName);
        parserVersion = DomainChecks.optionalText(parserVersion);
        if (completedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
    }

    public MaterialIngestAttempt withStage(IngestStage newStage) {
        return new MaterialIngestAttempt(jobId, materialId, attempt, newStage, failureCode, retryable,
                startedAt, completedAt, scanEngineVersion, scanSignatureVersion, parserName, parserVersion);
    }

    public MaterialIngestAttempt withScanVersions(String engineVersion, String signatureVersion) {
        return new MaterialIngestAttempt(jobId, materialId, attempt, stage, failureCode, retryable,
                startedAt, completedAt, engineVersion, signatureVersion, parserName, parserVersion);
    }

    public MaterialIngestAttempt withParserVersions(String name, String version) {
        return new MaterialIngestAttempt(jobId, materialId, attempt, stage, failureCode, retryable,
                startedAt, completedAt, scanEngineVersion, scanSignatureVersion, name, version);
    }
}
