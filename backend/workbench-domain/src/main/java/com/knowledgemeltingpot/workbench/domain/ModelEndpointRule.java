package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ModelEndpointRule(
        UUID id,
        String host,
        Set<Integer> allowedPorts,
        boolean allowHttp,
        boolean allowPrivateAddresses,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public ModelEndpointRule {
        if (id == null || host == null || host.isBlank() || allowedPorts == null || allowedPorts.isEmpty()
                || createdBy == null || updatedBy == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("model endpoint rule fields are required");
        }
        if (allowedPorts.stream().anyMatch(port -> port == null || port < 1 || port > 65_535)) {
            throw new IllegalArgumentException("model endpoint rule ports must be between 1 and 65535");
        }
        allowedPorts = Set.copyOf(allowedPorts);
    }
}
