package com.knowledgemeltingpot.workbench.application.port;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Port for multipart-presigned S3/MinIO operations and short-lived downloads.
 * Object keys are generated server-side and passed to the adapter together with
 * the storage zone so the correct bucket/prefix is used.
 */
public interface ObjectStoragePort {

    enum StorageZone {
        QUARANTINE,
        VERIFIED_KNOWLEDGE,
        VERIFIED_HOLDOUT,
        ASSETS
    }

    MultipartUpload initiateMultipart(StorageZone zone, String objectKey, String contentType, long sizeBytes,
            Duration expiry);

    List<PresignedPart> presignParts(StorageZone zone, String uploadId, String objectKey, int fromPart, int toPart,
            Duration expiry);

    ObjectHead completeMultipart(StorageZone zone, String uploadId, String objectKey, List<UploadedPart> parts);

    void abortMultipart(StorageZone zone, String uploadId, String objectKey);

    ObjectHead head(StorageZone zone, String objectKey);

    InputStream open(StorageZone zone, String objectKey);

    void copyToVerified(StorageZone sourceZone, String sourceKey, StorageZone targetZone, String targetKey);

    ObjectHead put(StorageZone zone, String objectKey, byte[] content, String contentType);

    URL presignDownload(StorageZone zone, String objectKey, Duration expiry);

    record MultipartUpload(String uploadId, String objectKey, long partSize, int partCount, Instant expiresAt) {
        public MultipartUpload {
            if (uploadId == null || uploadId.isBlank()) {
                throw new IllegalArgumentException("uploadId is required");
            }
            if (objectKey == null || objectKey.isBlank()) {
                throw new IllegalArgumentException("objectKey is required");
            }
            if (partSize < 1) {
                throw new IllegalArgumentException("partSize must be positive");
            }
            if (partCount < 1) {
                throw new IllegalArgumentException("partCount must be positive");
            }
            if (expiresAt == null) {
                throw new IllegalArgumentException("expiresAt is required");
            }
        }
    }

    record PresignedPart(int partNumber, URL url, Map<String, String> requiredHeaders) {
        public PresignedPart {
            if (partNumber < 1) {
                throw new IllegalArgumentException("partNumber must be positive");
            }
            if (url == null) {
                throw new IllegalArgumentException("url is required");
            }
            requiredHeaders = requiredHeaders == null ? Map.of() : Map.copyOf(requiredHeaders);
        }
    }

    record UploadedPart(int partNumber, String etag) {
        public UploadedPart {
            if (partNumber < 1) {
                throw new IllegalArgumentException("partNumber must be positive");
            }
            if (etag == null || etag.isBlank()) {
                throw new IllegalArgumentException("etag is required");
            }
        }
    }

    record ObjectHead(String objectKey, long sizeBytes, String etag, Instant lastModified) {
        public ObjectHead {
            if (objectKey == null || objectKey.isBlank()) {
                throw new IllegalArgumentException("objectKey is required");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            if (etag == null) {
                throw new IllegalArgumentException("etag is required");
            }
            if (lastModified == null) {
                throw new IllegalArgumentException("lastModified is required");
            }
        }
    }
}
