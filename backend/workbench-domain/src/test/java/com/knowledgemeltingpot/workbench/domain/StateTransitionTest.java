package com.knowledgemeltingpot.workbench.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StateTransitionTest {

    @Test
    void jobCanOnlyFinishAfterItStarts() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        Job queued = new Job(UUID.randomUUID(), JobType.EXTRACT, "SUB_SCENE", UUID.randomUUID(),
                JobStatus.QUEUED, 0, 0, "{}", "", "", "", UUID.randomUUID(), now, now);

        Job running = queued.transitionTo(JobStatus.RUNNING, 1, now.plusSeconds(1));

        assertThat(running.transitionTo(JobStatus.SUCCEEDED, 100, now.plusSeconds(2)).status())
                .isEqualTo(JobStatus.SUCCEEDED);
        assertThatThrownBy(() -> queued.transitionTo(JobStatus.SUCCEEDED, 100, now))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readyAssetRequiresAddressableContent() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");

        assertThatThrownBy(() -> new Asset(UUID.randomUUID(), UUID.randomUUID(), AssetType.RULE_CATALOG,
                1, AssetStatus.READY, UUID.randomUUID(), "", "", "", now, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
