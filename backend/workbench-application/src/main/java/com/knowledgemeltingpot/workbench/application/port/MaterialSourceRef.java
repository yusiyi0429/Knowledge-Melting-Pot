package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import java.time.Instant;
import java.util.UUID;

/**
 * A structured source reference derived exclusively from the persisted chunk
 * locator and material identity. Client input never contributes to these
 * fields.
 */
public record MaterialSourceRef(
        String code,
        UUID materialId,
        String materialSha256,
        UUID chunkId,
        String locatorType,
        Integer page,
        Integer paragraph,
        Integer table,
        String sheet,
        Integer rowStart,
        Integer rowEnd,
        Integer colStart,
        Integer colEnd,
        Integer lineStart,
        Integer lineEnd,
        String excerptHash,
        int charCount,
        Instant chunkCreatedAt) {

    public MaterialSourceRef {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (materialId == null || chunkId == null || locatorType == null || locatorType.isBlank()
                || excerptHash == null || excerptHash.isBlank() || chunkCreatedAt == null) {
            throw new IllegalArgumentException("source ref fields are required");
        }
    }

    public static MaterialSourceRef from(Material material, MaterialChunk chunk) {
        var locator = chunk.locator();
        return new MaterialSourceRef(
                chunk.sourceRefCode(),
                material.id(),
                material.sha256(),
                chunk.id(),
                locator.type().name(),
                locator.page(),
                locator.paragraph(),
                locator.table(),
                locator.sheet(),
                locator.rowStart(),
                locator.rowEnd(),
                locator.colStart(),
                locator.colEnd(),
                locator.lineStart(),
                locator.lineEnd(),
                chunk.contentHash(),
                chunk.charCount(),
                chunk.createdAt());
    }
}
