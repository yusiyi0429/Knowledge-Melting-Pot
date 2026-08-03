package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record MaterialUploadIntent(
        UUID id,
        UUID materialId,
        UUID createdBy,
        UUID validationJobId,
        String clientEtag,
        Instant createdAt,
        Instant completedAt,
        String storageUploadId,
        String quarantineObjectKey,
        Long partSize,
        Integer partCount,
        Instant expiresAt,
        UploadState uploadState,
        int completionAttempt) {

    public MaterialUploadIntent {
        id = DomainChecks.required(id, "id");
        materialId = DomainChecks.required(materialId, "materialId");
        createdBy = DomainChecks.required(createdBy, "createdBy");
        clientEtag = DomainChecks.optionalText(clientEtag);
        createdAt = DomainChecks.required(createdAt, "createdAt");
        storageUploadId = DomainChecks.optionalText(storageUploadId);
        quarantineObjectKey = DomainChecks.optionalText(quarantineObjectKey);
        uploadState = DomainChecks.required(uploadState, "uploadState");
        if (validationJobId != null && completedAt == null) {
            throw new IllegalArgumentException("validationJobId requires completedAt");
        }
        if (uploadState == UploadState.COMPLETED && (validationJobId == null || completedAt == null)) {
            throw new IllegalArgumentException("completed upload requires a validation job and completedAt");
        }
        if ((uploadState == UploadState.ABORTED || uploadState == UploadState.EXPIRED) && completedAt == null) {
            throw new IllegalArgumentException("terminal upload requires completedAt");
        }
        if (uploadState != UploadState.COMPLETED && validationJobId != null) {
            throw new IllegalArgumentException("validationJobId is only valid for completed uploads");
        }
        if ((partSize == null) != (partCount == null) || (partCount == null) != (expiresAt == null)) {
            throw new IllegalArgumentException("partSize, partCount and expiresAt must be set together");
        }
        if (partSize != null && partSize < 1) {
            throw new IllegalArgumentException("partSize must be positive");
        }
        if (partCount != null && partCount < 1) {
            throw new IllegalArgumentException("partCount must be positive");
        }
        if (completionAttempt < 0) {
            throw new IllegalArgumentException("completionAttempt must not be negative");
        }
    }

    public static MaterialUploadIntent declarationOnly(UUID id, UUID materialId, UUID createdBy, Instant createdAt) {
        return new MaterialUploadIntent(id, materialId, createdBy, null, "", createdAt, null,
                "", "", null, null, null, UploadState.INITIATED, 0);
    }

    public static MaterialUploadIntent multipart(
            UUID id,
            UUID materialId,
            UUID createdBy,
            Instant createdAt,
            String storageUploadId,
            String quarantineObjectKey,
            long partSize,
            int partCount,
            Instant expiresAt) {
        return new MaterialUploadIntent(id, materialId, createdBy, null, "", createdAt, null,
                storageUploadId, quarantineObjectKey, partSize, partCount, expiresAt, UploadState.INITIATED, 0);
    }

    public MaterialUploadIntent withUploadState(UploadState state) {
        return new MaterialUploadIntent(id, materialId, createdBy, validationJobId, clientEtag,
                createdAt, completedAt, storageUploadId, quarantineObjectKey, partSize, partCount,
                expiresAt, state, completionAttempt);
    }

    public MaterialUploadIntent completed(UUID jobId, String etag, Instant completedAt) {
        return new MaterialUploadIntent(id, materialId, createdBy, jobId, etag,
                createdAt, completedAt, storageUploadId, quarantineObjectKey, partSize, partCount,
                expiresAt, UploadState.COMPLETED, completionAttempt);
    }

    public boolean isMultipart() {
        return partSize != null;
    }
}
