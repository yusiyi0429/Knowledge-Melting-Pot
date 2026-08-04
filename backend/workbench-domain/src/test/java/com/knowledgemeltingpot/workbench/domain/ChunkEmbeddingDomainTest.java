package com.knowledgemeltingpot.workbench.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkEmbeddingDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void chunkLocatorRejectsInvertedRanges() {
        assertThatThrownBy(() -> new ChunkLocator(ChunkLocator.LocatorType.TXT_LINES, null, null, null, null,
                null, null, null, null, 5, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineEnd");
    }

    @Test
    void chunkLocatorRejectsNegativeCoordinates() {
        assertThatThrownBy(() -> new ChunkLocator(ChunkLocator.LocatorType.XLSX_RANGE, null, null, null, "S",
                -1, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rowStart");
    }

    @Test
    void materialChunkComputesContentHashServerSide() {
        MaterialChunk chunk = MaterialChunk.fromParsed(UUID.randomUUID(), UUID.randomUUID(), 0, "SRC-abc-0",
                new ChunkLocator(ChunkLocator.LocatorType.TXT_LINES, null, null, null, null, null, null, null,
                        null, 0, 0),
                "hello world", "1", NOW);

        assertThat(chunk.contentHash()).matches("[0-9a-f]{64}");
        assertThat(chunk.charCount()).isEqualTo(11);
        assertThat(chunk.contentHash()).isEqualTo(MaterialChunk.fromParsed(UUID.randomUUID(), UUID.randomUUID(), 0,
                "SRC-abc-0", chunk.locator(), "hello world", "1", NOW).contentHash());
    }

    @Test
    void materialChunkRejectsHashCharCountMismatch() {
        MaterialChunk base = MaterialChunk.fromParsed(UUID.randomUUID(), UUID.randomUUID(), 0, "SRC-abc-0",
                new ChunkLocator(ChunkLocator.LocatorType.TXT_LINES, null, null, null, null, null, null, null,
                        null, 0, 0),
                "hello", "1", NOW);
        assertThatThrownBy(() -> new MaterialChunk(base.id(), base.blobId(), 0, "SRC-abc-0", base.locator(),
                "hello", base.contentHash(), 9, "1", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("charCount");
    }

    @Test
    void embeddingProfileRejectsInvalidDimensions() {
        assertThatThrownBy(() -> new EmbeddingProfileVersion(UUID.randomUUID(), "p", "m", 0, "1", "L2", "COSINE",
                true, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void chunkEmbeddingRejectsDimensionMismatch() {
        assertThatThrownBy(() -> new ChunkEmbedding(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), 3,
                List.of(1f, 2f), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }
}
