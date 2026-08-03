package com.knowledgemeltingpot.workbench.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ModelConfigVersion(
        UUID id,
        UUID modelConnectionId,
        int version,
        String modelId,
        BigDecimal temperature,
        int maxOutputTokens,
        UUID createdBy,
        Instant createdAt) {

    public ModelConfigVersion {
        id = DomainChecks.required(id, "id");
        modelConnectionId = DomainChecks.required(modelConnectionId, "modelConnectionId");
        modelId = DomainChecks.text(modelId, "modelId");
        temperature = DomainChecks.required(temperature, "temperature");
        createdBy = DomainChecks.required(createdBy, "createdBy");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (temperature.compareTo(BigDecimal.ZERO) < 0 || temperature.compareTo(new BigDecimal("2.00")) > 0) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 1_000_000) {
            throw new IllegalArgumentException("maxOutputTokens must be between 1 and 1000000");
        }
    }
}
