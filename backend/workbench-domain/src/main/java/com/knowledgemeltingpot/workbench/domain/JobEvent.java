package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record JobEvent(
        long sequence,
        UUID jobId,
        String eventType,
        String payloadJson,
        Instant occurredAt) {

    public JobEvent {
        jobId = DomainChecks.required(jobId, "jobId");
        eventType = DomainChecks.text(eventType, "eventType");
        payloadJson = DomainChecks.text(payloadJson, "payloadJson");
        occurredAt = DomainChecks.required(occurredAt, "occurredAt");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
    }
}
