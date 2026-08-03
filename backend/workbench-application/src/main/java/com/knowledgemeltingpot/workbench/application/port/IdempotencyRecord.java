package com.knowledgemeltingpot.workbench.application.port;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(
        String scope,
        String key,
        String requestHash,
        String resourceType,
        UUID resourceId,
        Instant createdAt,
        Instant expiresAt) {
}
