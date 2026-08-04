package com.knowledgemeltingpot.workbench.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssetStatusTransitionTest {
    @Test
    void blockedIsADistinctRetryableState() {
        assertThat(AssetStatus.PENDING.canTransitionTo(AssetStatus.BLOCKED)).isTrue();
        assertThat(AssetStatus.GENERATING.canTransitionTo(AssetStatus.BLOCKED)).isTrue();
        assertThat(AssetStatus.BLOCKED.canTransitionTo(AssetStatus.GENERATING)).isTrue();
        assertThat(AssetStatus.BLOCKED.canTransitionTo(AssetStatus.SUPERSEDED)).isTrue();
    }

    @Test
    void blockedCannotPretendReadinessOrFailure() {
        assertThat(AssetStatus.BLOCKED.canTransitionTo(AssetStatus.READY)).isFalse();
        assertThat(AssetStatus.BLOCKED.canTransitionTo(AssetStatus.FAILED)).isFalse();
        assertThat(AssetStatus.BLOCKED.canTransitionTo(AssetStatus.BLOCKED)).isFalse();
    }

    @Test
    void existingTransitionsRemainStable() {
        assertThat(AssetStatus.PENDING.canTransitionTo(AssetStatus.GENERATING)).isTrue();
        assertThat(AssetStatus.GENERATING.canTransitionTo(AssetStatus.READY)).isTrue();
        assertThat(AssetStatus.GENERATING.canTransitionTo(AssetStatus.FAILED)).isTrue();
        assertThat(AssetStatus.FAILED.canTransitionTo(AssetStatus.GENERATING)).isTrue();
        assertThat(AssetStatus.READY.canTransitionTo(AssetStatus.SUPERSEDED)).isTrue();
        assertThat(AssetStatus.SUPERSEDED.canTransitionTo(AssetStatus.READY)).isFalse();
    }
}
