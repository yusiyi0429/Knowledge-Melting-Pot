package com.knowledgemeltingpot.workbench.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * One immutable content unit of a verified material blob. Chunks are produced
 * by a specific parser version and are never rewritten: the unique key
 * (blob_id, parser_version, ordinal) guarantees a verified blob is parsed only
 * once per parser version.
 */
public record MaterialChunk(
        UUID id,
        UUID blobId,
        int ordinal,
        String sourceRefCode,
        ChunkLocator locator,
        String content,
        String contentHash,
        int charCount,
        String parserVersion,
        Instant createdAt) {

    public MaterialChunk {
        id = DomainChecks.required(id, "id");
        blobId = DomainChecks.required(blobId, "blobId");
        ordinal = DomainChecks.nonNegative(ordinal, "ordinal");
        sourceRefCode = DomainChecks.text(sourceRefCode, "sourceRefCode");
        locator = DomainChecks.required(locator, "locator");
        content = DomainChecks.required(content, "content");
        contentHash = DomainChecks.text(contentHash, "contentHash");
        parserVersion = DomainChecks.text(parserVersion, "parserVersion");
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (charCount < 0) {
            throw new IllegalArgumentException("charCount must not be negative");
        }
        if (charCount != content.length()) {
            throw new IllegalArgumentException("charCount must equal the content length");
        }
        if (!contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a lowercase 64-character hexadecimal digest");
        }
    }

    /**
     * Builds a chunk from parser output; the content hash is always computed
     * server-side from the extracted text, never accepted from a client.
     */
    public static MaterialChunk fromParsed(UUID id, UUID blobId, int ordinal, String sourceRefCode,
            ChunkLocator locator, String content, String parserVersion, Instant createdAt) {
        String contentHash = sha256(content);
        return new MaterialChunk(id, blobId, ordinal, sourceRefCode, locator, content, contentHash,
                content.length(), parserVersion, createdAt);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
