package com.knowledgemeltingpot.workbench.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaterialBlobTest {

    @Test
    void requiresValidSha256() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        assertThatThrownBy(() -> new MaterialBlob(UUID.randomUUID(), SecurityPartition.KNOWLEDGE,
                "NOTHEX", "verified/abc", 1024, "application/pdf", "1.0", "20240801", "pdfbox", "3.0", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verifiedSha256");
    }

    @Test
    void requiresPositiveSize() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        assertThatThrownBy(() -> new MaterialBlob(UUID.randomUUID(), SecurityPartition.KNOWLEDGE,
                "a".repeat(64), "verified/abc", 0, "application/pdf", "1.0", "20240801", "pdfbox", "3.0", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeBytes");
    }
}
