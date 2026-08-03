package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        UUID actorId,
        String action,
        String targetType,
        UUID targetId,
        String detailsJson,
        String traceId,
        Instant occurredAt) {

    public AuditEvent {
        id = DomainChecks.required(id, "id");
        actorId = DomainChecks.required(actorId, "actorId");
        action = DomainChecks.text(action, "action");
        targetType = DomainChecks.text(targetType, "targetType");
        targetId = DomainChecks.required(targetId, "targetId");
        detailsJson = DomainChecks.text(detailsJson, "detailsJson");
        traceId = DomainChecks.optionalText(traceId);
        occurredAt = DomainChecks.required(occurredAt, "occurredAt");
    }
}
