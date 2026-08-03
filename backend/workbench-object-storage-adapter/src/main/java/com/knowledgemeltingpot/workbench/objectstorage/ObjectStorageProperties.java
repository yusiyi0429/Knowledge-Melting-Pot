package com.knowledgemeltingpot.workbench.objectstorage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "workbench.object-storage")
public record ObjectStorageProperties(
        boolean enabled,
        String internalEndpoint,
        String publicEndpoint,
        String region,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        Duration presignUploadTimeout,
        Duration presignDownloadTimeout,
        Long defaultPartSize,
        Buckets buckets) {

    @ConstructorBinding
    public ObjectStorageProperties {
        if (enabled) {
            if (internalEndpoint == null || internalEndpoint.isBlank()) {
                throw new IllegalArgumentException("workbench.object-storage.internal-endpoint is required");
            }
            if (region == null || region.isBlank()) {
                throw new IllegalArgumentException("workbench.object-storage.region is required");
            }
            if (accessKey == null || accessKey.isBlank()) {
                throw new IllegalArgumentException("workbench.object-storage.access-key is required");
            }
            if (secretKey == null || secretKey.isBlank()) {
                throw new IllegalArgumentException("workbench.object-storage.secret-key is required");
            }
        }
        if (publicEndpoint == null) {
            publicEndpoint = "";
        }
        if (presignUploadTimeout == null) {
            presignUploadTimeout = Duration.ofMinutes(10);
        }
        if (presignDownloadTimeout == null) {
            presignDownloadTimeout = Duration.ofMinutes(5);
        }
        if (defaultPartSize == null || defaultPartSize < 5L * 1024 * 1024) {
            defaultPartSize = 8L * 1024 * 1024;
        }
        if (buckets == null) {
            buckets = new Buckets("kmp-quarantine", "kmp-verified-knowledge", "kmp-verified-holdout", "kmp-assets");
        }
    }

    public String resolvedPublicEndpoint() {
        return publicEndpoint.isBlank() ? internalEndpoint : publicEndpoint;
    }

    public record Buckets(String quarantine, String verifiedKnowledge, String verifiedHoldout, String assets) {
    }
}
