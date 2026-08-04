package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record UserNotification(
        UUID id,
        UUID userId,
        String type,
        String title,
        String message,
        String resourceType,
        UUID resourceId,
        Instant createdAt,
        Instant readAt) {

    public UserNotification {
        id = DomainChecks.required(id, "id");
        userId = DomainChecks.required(userId, "userId");
        type = DomainChecks.text(type, "type");
        title = DomainChecks.text(title, "title");
        message = DomainChecks.optionalText(message);
        resourceType = DomainChecks.text(resourceType, "resourceType");
        resourceId = DomainChecks.required(resourceId, "resourceId");
        createdAt = DomainChecks.required(createdAt, "createdAt");
    }

    public boolean read() {
        return readAt != null;
    }
}
