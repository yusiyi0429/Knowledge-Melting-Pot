package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record Job(
        UUID id,
        JobType type,
        String aggregateType,
        UUID aggregateId,
        JobStatus status,
        int progress,
        int attempt,
        String payloadJson,
        String resultReference,
        String errorCode,
        String errorMessage,
        UUID requestedBy,
        Instant createdAt,
        Instant updatedAt) {

    public Job {
        id = DomainChecks.required(id, "id");
        type = DomainChecks.required(type, "type");
        aggregateType = DomainChecks.text(aggregateType, "aggregateType");
        aggregateId = DomainChecks.required(aggregateId, "aggregateId");
        status = DomainChecks.required(status, "status");
        payloadJson = DomainChecks.optionalText(payloadJson);
        resultReference = DomainChecks.optionalText(resultReference);
        errorCode = DomainChecks.optionalText(errorCode);
        errorMessage = DomainChecks.optionalText(errorMessage);
        requestedBy = DomainChecks.required(requestedBy, "requestedBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
    }

    public Job transitionTo(JobStatus target, int nextProgress, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("invalid job transition: " + status + " -> " + target);
        }
        return new Job(id, type, aggregateType, aggregateId, target, nextProgress, attempt, payloadJson,
                resultReference, errorCode, errorMessage, requestedBy, createdAt, now);
    }
}
