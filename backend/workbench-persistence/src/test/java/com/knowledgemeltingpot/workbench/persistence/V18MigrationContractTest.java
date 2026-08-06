package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V18MigrationContractTest {
    @Test
    void addsRecoverableSceneArchivalMetadata() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/V18__scene_soft_deletion.sql")) {
            assertThat(stream).isNotNull();
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(migration)
                    .contains("archived_at")
                    .contains("archived_by")
                    .contains("where archived_at is null");
        }
    }
}
