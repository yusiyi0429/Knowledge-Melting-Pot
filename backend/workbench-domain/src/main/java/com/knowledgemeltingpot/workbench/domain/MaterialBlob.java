package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.UUID;

public record MaterialBlob(
        UUID id,
        SecurityPartition securityPartition,
        String verifiedSha256,
        String cleanObjectKey,
        long sizeBytes,
        String detectedMime,
        String scanEngineVersion,
        String scanSignatureVersion,
        String parserName,
        String parserVersion,
        Instant createdAt) {

    public MaterialBlob {
        id = DomainChecks.required(id, "id");
        securityPartition = DomainChecks.required(securityPartition, "securityPartition");
        verifiedSha256 = DomainChecks.text(verifiedSha256, "verifiedSha256");
        cleanObjectKey = DomainChecks.text(cleanObjectKey, "cleanObjectKey");
        sizeBytes = DomainChecks.positive(sizeBytes, "sizeBytes");
        detectedMime = DomainChecks.text(detectedMime, "detectedMime");
        scanEngineVersion = DomainChecks.text(scanEngineVersion, "scanEngineVersion");
        scanSignatureVersion = DomainChecks.text(scanSignatureVersion, "scanSignatureVersion");
        parserName = DomainChecks.optionalText(parserName);
        parserVersion = DomainChecks.optionalText(parserVersion);
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (!verifiedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("verifiedSha256 must be a lowercase 64-character hexadecimal digest");
        }
    }
}
