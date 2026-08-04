package com.knowledgemeltingpot.workbench.objectstorage;

import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;

public class S3ObjectStorageAdapter implements ObjectStoragePort {

    private static final int MAX_PART_COUNT = 10000;
    private static final long MIN_PART_SIZE = 5L * 1024 * 1024;

    private final S3Client client;
    private final S3Presigner internalPresigner;
    private final S3Presigner publicPresigner;
    private final ObjectStorageProperties properties;

    public S3ObjectStorageAdapter(S3Client client, S3Presigner internalPresigner, S3Presigner publicPresigner,
            ObjectStorageProperties properties) {
        this.client = client;
        this.internalPresigner = internalPresigner;
        this.publicPresigner = publicPresigner;
        this.properties = properties;
    }

    @Override
    public MultipartUpload initiateMultipart(StorageZone zone, String objectKey, String contentType, long sizeBytes,
            Duration expiry) {
        String key = safeObjectKey(objectKey);
        String bucket = bucketForZone(zone);
        long partSize = properties.defaultPartSize();
        int partCount = (int) ((sizeBytes + partSize - 1) / partSize);
        if (partCount > MAX_PART_COUNT) {
            partSize = Math.max(MIN_PART_SIZE, (sizeBytes + MAX_PART_COUNT - 1) / MAX_PART_COUNT);
            partCount = (int) ((sizeBytes + partSize - 1) / partSize);
        }
        if (partCount < 1) {
            partCount = 1;
        }

        var request = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        var response = client.createMultipartUpload(request);
        return new MultipartUpload(response.uploadId(), key, partSize, partCount, Instant.now().plus(expiry));
    }

    @Override
    public List<PresignedPart> presignParts(StorageZone zone, String uploadId, String objectKey, int fromPart,
            int toPart, Duration expiry) {
        String key = safeObjectKey(objectKey);
        String bucket = bucketForZone(zone);
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId is required");
        }
        if (fromPart < 1 || toPart < fromPart) {
            throw new IllegalArgumentException("invalid part range");
        }
        List<PresignedPart> parts = new ArrayList<>();
        for (int partNumber = fromPart; partNumber <= toPart; partNumber++) {
            var uploadPartRequest = UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();
            var presignRequest = software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest.builder()
                    .signatureDuration(expiry)
                    .uploadPartRequest(uploadPartRequest)
                    .build();
            PresignedUploadPartRequest presigned = publicPresigner.presignUploadPart(presignRequest);
            Map<String, String> headers = presigned.signedHeaders().entrySet().stream()
                    .filter(entry -> !"host".equalsIgnoreCase(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> String.join(", ", entry.getValue())));
            parts.add(new PresignedPart(partNumber, presigned.url(), headers));
        }
        return parts;
    }

    @Override
    public ObjectHead completeMultipart(StorageZone zone, String uploadId, String objectKey,
            List<UploadedPart> parts) {
        String key = safeObjectKey(objectKey);
        String bucket = bucketForZone(zone);
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId is required");
        }
        List<CompletedPart> completedParts = parts.stream()
                .sorted(java.util.Comparator.comparingInt(UploadedPart::partNumber))
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.etag())
                        .build())
                .toList();
        var request = CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build();
        client.completeMultipartUpload(request);
        return head(zone, key);
    }

    @Override
    public void abortMultipart(StorageZone zone, String uploadId, String objectKey) {
        String key = safeObjectKey(objectKey);
        String bucket = bucketForZone(zone);
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId is required");
        }
        client.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId));
    }

    @Override
    public ObjectHead head(StorageZone zone, String objectKey) {
        String key = safeObjectKey(objectKey);
        String bucket = bucketForZone(zone);
        var request = HeadObjectRequest.builder().bucket(bucket).key(key).build();
        var response = client.headObject(request);
        return new ObjectHead(key, response.contentLength(), nullSafeEtag(response.eTag()),
                response.lastModified());
    }

    @Override
    public InputStream open(StorageZone zone, String objectKey) {
        String key = safeObjectKey(objectKey);
        String bucket = bucketForZone(zone);
        var request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        ResponseInputStream<?> stream = client.getObject(request);
        return stream;
    }

    @Override
    public void copyToVerified(StorageZone sourceZone, String sourceKey, StorageZone targetZone, String targetKey) {
        String srcKey = safeObjectKey(sourceKey);
        String dstKey = safeObjectKey(targetKey);
        String sourceBucket = bucketForZone(sourceZone);
        String targetBucket = bucketForZone(targetZone);
        var request = CopyObjectRequest.builder()
                .sourceBucket(sourceBucket)
                .sourceKey(srcKey)
                .destinationBucket(targetBucket)
                .destinationKey(dstKey)
                .build();
        client.copyObject(request);
    }

    @Override
    public ObjectHead put(StorageZone zone, String objectKey, byte[] content, String contentType) {
        String key = safeObjectKey(objectKey);
        String bucket = bucketForZone(zone);
        var request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        client.putObject(request, RequestBody.fromBytes(content));
        return head(zone, key);
    }

    @Override
    public URL presignDownload(StorageZone zone, String objectKey, Duration expiry) {
        String key = safeObjectKey(objectKey);
        String bucket = bucketForZone(zone);
        var getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
        var presignRequest = software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(getRequest)
                .build();
        return publicPresigner.presignGetObject(presignRequest).url();
    }

    static String safeObjectKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("objectKey is required");
        }
        if (key.length() > 1024) {
            throw new IllegalArgumentException("objectKey exceeds 1024 characters");
        }
        if (key.charAt(0) == '/' || key.indexOf('\\') >= 0 || key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("objectKey contains an illegal character or absolute path");
        }
        int start = 0;
        while (start <= key.length()) {
            int slash = key.indexOf('/', start);
            int end = slash == -1 ? key.length() : slash;
            if (end == start) {
                throw new IllegalArgumentException("objectKey contains an empty path segment");
            }
            String segment = key.substring(start, end);
            if (segment.equals("..")) {
                throw new IllegalArgumentException("objectKey contains a parent-directory reference");
            }
            if (slash == -1) {
                break;
            }
            start = slash + 1;
        }
        return key;
    }

    private String bucketForZone(StorageZone zone) {
        return switch (zone) {
            case QUARANTINE -> properties.buckets().quarantine();
            case VERIFIED_KNOWLEDGE -> properties.buckets().verifiedKnowledge();
            case VERIFIED_HOLDOUT -> properties.buckets().verifiedHoldout();
            case ASSETS -> properties.buckets().assets();
        };
    }

    private static String nullSafeEtag(String etag) {
        return etag == null ? "" : etag;
    }
}
