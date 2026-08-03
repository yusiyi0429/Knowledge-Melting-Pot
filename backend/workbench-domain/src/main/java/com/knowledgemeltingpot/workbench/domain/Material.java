package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record Material(
        UUID id,
        String fileName,
        MaterialFormat format,
        String mediaType,
        String objectKey,
        String sha256,
        long sizeBytes,
        MaterialStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

    public Material {
        id = DomainChecks.required(id, "id");
        fileName = DomainChecks.text(fileName, "fileName");
        format = DomainChecks.required(format, "format");
        mediaType = DomainChecks.text(mediaType, "mediaType");
        objectKey = DomainChecks.text(objectKey, "objectKey");
        sha256 = DomainChecks.text(sha256, "sha256");
        status = DomainChecks.required(status, "status");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase 64-character hexadecimal digest");
        }
        if (sizeBytes < 1 || sizeBytes > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("sizeBytes must be between 1 and 209715200");
        }
    }

    public Material transitionTo(MaterialStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("invalid material transition: " + status + " -> " + target);
        }
        return new Material(id, fileName, format, mediaType, objectKey, sha256, sizeBytes, target, createdAt, now);
    }
}
