package com.knowledgemeltingpot.workbench.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaterialIsolationTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void lifecycleTransitionPreservesImmutableFileMetadata() {
        Material pending = material(MaterialStatus.PENDING_UPLOAD);

        Material uploaded = pending.transitionTo(MaterialStatus.UPLOADED, NOW.plusSeconds(1));

        assertThat(uploaded.status()).isEqualTo(MaterialStatus.UPLOADED);
        assertThat(uploaded.fileName()).isEqualTo(pending.fileName());
        assertThat(uploaded.objectKey()).isEqualTo(pending.objectKey());
        assertThat(uploaded.sha256()).isEqualTo(pending.sha256());
        assertThat(uploaded.sizeBytes()).isEqualTo(pending.sizeBytes());
        assertThat(uploaded.createdAt()).isEqualTo(pending.createdAt());
    }

    @Test
    void holdoutCanNeverBeMarkedAsRegulatoryAlignmentSource() {
        assertThatThrownBy(() -> new RoundMaterial(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), MaterialPartition.LABELED_HOLDOUT, MaterialShareScope.ROUND,
                true, true, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holdout");
    }

    @Test
    void partitionVisibilityIsMutuallyExclusive() {
        assertThat(MaterialPartition.SOURCE.knowledgeVisible()).isTrue();
        assertThat(MaterialPartition.LABELED_TRAIN.knowledgeVisible()).isTrue();
        assertThat(MaterialPartition.LABELED_HOLDOUT.knowledgeVisible()).isFalse();
        assertThat(MaterialPartition.LABELED_HOLDOUT.evaluationVisible()).isTrue();
        assertThat(MaterialPartition.SOURCE.evaluationVisible()).isFalse();
    }

    private Material material(MaterialStatus status) {
        return new Material(UUID.randomUUID(), "policy.pdf", MaterialFormat.PDF, "application/pdf",
                "quarantine/object", "a".repeat(64), Material.MAX_UPLOAD_BYTES, status, NOW, NOW);
    }
}
