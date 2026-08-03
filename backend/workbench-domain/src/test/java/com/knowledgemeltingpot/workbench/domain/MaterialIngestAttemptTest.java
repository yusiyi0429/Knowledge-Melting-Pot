package com.knowledgemeltingpot.workbench.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaterialIngestAttemptTest {

    @Test
    void completedAtMustNotBeBeforeStartedAt() {
        Instant startedAt = Instant.parse("2026-08-03T10:00:00Z");
        Instant completedAt = startedAt.minusSeconds(1);
        assertThatThrownBy(() -> new MaterialIngestAttempt(UUID.randomUUID(), UUID.randomUUID(), 1,
                IngestStage.STARTED, null, false, startedAt, completedAt, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completedAt");
    }

    @Test
    void withStageReturnsNewInstance() {
        Instant now = Instant.parse("2026-08-03T10:00:00Z");
        MaterialIngestAttempt attempt = new MaterialIngestAttempt(UUID.randomUUID(), UUID.randomUUID(), 1,
                IngestStage.STARTED, null, false, now, null, null, null, null, null);
        MaterialIngestAttempt advanced = attempt.withStage(IngestStage.HEAD_VERIFIED);
        assertThat(advanced.stage()).isEqualTo(IngestStage.HEAD_VERIFIED);
        assertThat(attempt.stage()).isEqualTo(IngestStage.STARTED);
    }
}
