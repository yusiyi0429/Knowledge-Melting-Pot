package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record Asset(
        UUID id,
        UUID subSceneId,
        AssetType type,
        int version,
        AssetStatus status,
        UUID documentRevisionId,
        String objectKey,
        String checksum,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public Asset {
        id = DomainChecks.required(id, "id");
        subSceneId = DomainChecks.required(subSceneId, "subSceneId");
        type = DomainChecks.required(type, "type");
        status = DomainChecks.required(status, "status");
        objectKey = DomainChecks.optionalText(objectKey);
        checksum = DomainChecks.optionalText(checksum);
        failureReason = DomainChecks.optionalText(failureReason);
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (status == AssetStatus.READY && (objectKey.isBlank() || checksum.isBlank())) {
            throw new IllegalArgumentException("ready asset requires objectKey and checksum");
        }
    }
}
